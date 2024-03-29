package io.legs.specialized

import io.legs.Specialization
import scala.util.{Failure, Success}
import io.legs.scheduling.{JobStatus, Priority, JobType, Job}
import io.legs.utils.{JsonFriend, EnumJson, RedisProvider}
import play.api.libs.json.{JsString, Json}
import org.joda.time.{DateTimeZone, DateTime}
import redis.protocol.{RedisReply, Bulk}
import redis.api.scripting.RedisScript
import java.util.logging.{Level, Logger}
import io.legs.Specialization.{RoutableFuture, Yield}
import scala.concurrent._
import com.uniformlyrandom.scron.Scron

object Queue extends Specialization {

	private lazy val logger = Logger.getLogger(this.getClass.getSimpleName)

	final val jobsData_HS = "legs:jobs"
	final val jobsCounterKey_S = "legs:jobs:counter"
	final val maxRetries = 5

	final val schedulePlansKey_HS = "legs:schedule:plans"

	final val queueByLabelPrefix_ZL = "legs:queue:label:"
	final def queueByLabelKey_ZL(label: String) = s"$queueByLabelPrefix_ZL$label"
	final val queueWorkingByLabelPrefix_ZL = "legs:queue:working:label:"
	final def queueWorkingByLabelKey_ZL(label:String) = s"$queueWorkingByLabelPrefix_ZL$label"
	final val queueDeferredByLabelPrefix_ZL = "legs:queue:deferred:label:"
	final def queueDeferredByLabelKey_ZL(label:String) = s"$queueDeferredByLabelPrefix_ZL$label" 

	private val planAheadHours = 2
	val jobsStartValue = "1000"

	object Plans {
		val oncePerHour = "0 0 * * * *"
	}

	val getSchedulerJob = Job(
		instructions = "scheduler",
		labels = List("scheduler"),
		input = Map.empty,
		description = "queues all scheduled jobs",
		jobType = JobType.SCHEDULE_JOB,
		priority = Priority.HIGH,
		id = "100"
	)

	private val setupRedisLua =
		s"""
		  |local jobsCounterKey = '$jobsCounterKey_S'
		  |local jobId = ${getSchedulerJob.id}
		  |
		  |if not redis.call('GET', jobsCounterKey) then
		  |	redis.call('SET', jobsCounterKey, '$jobsStartValue')
		  |end
		  |
		  |if not redis.call('HGET', '$jobsData_HS', jobId ) then
		  |	redis.call('HSET', '$jobsData_HS', jobId, '${Json.toJson(getSchedulerJob).toString()}')
		  |end
		""".stripMargin

	def setupRedis() = {
		logger.info("setting up redis")
		writeJobPlan(getSchedulerJob.id, "0 0 * * * *")
		RedisProvider.redisPool.eval(setupRedisLua)
	}

	def persistJob(job:Job) =
		RedisProvider.redisPool.hset(jobsData_HS,job.id,Json.stringify(Json.toJson(job)))

	def queueJobImmidietly(job: Job) =
		persistJobQueue(job, DateTime.now(DateTimeZone.UTC).getMillis)


	def getJob(id: String) : Option[Job] =
		RedisProvider.blocking
			{ _.hget[String](jobsData_HS,id) } match {
				case Some(jobString) =>
					Json.parse(jobString).asOpt[Job]
				case None => None
			}

	def deleteJob(job: Job) {

		logger.info(s"deleting job ${job.id} from queue")

		RedisProvider.blockingList {
			c=>
				job.labels.map(l=>{
					c.zrem(queueByLabelKey_ZL(l),job.id)
					c.zrem(queueWorkingByLabelKey_ZL(l),job.id)
					c.zrem(queueDeferredByLabelKey_ZL(l),job.id)
				}):::List(
					c.hdel(jobsData_HS, job.id),
					c.hdel(schedulePlansKey_HS, job.id)
				)
		}


	}
	
	private def getScheduleForJob(id : String) : Option[String] =
		RedisProvider.blocking {
			_.hget[String](schedulePlansKey_HS, id)
		}


	def getAllScheduledJobs =
		RedisProvider.blocking {
			_.hgetall[String](schedulePlansKey_HS)
		}

	def getNextJobId : String =
		RedisProvider.blocking {
			_.incr(jobsCounterKey_S)
		}.toString

	private def persistJobQueue(job: Job, timeMillis: Long ){
		logger.info(s"persisting job in queue jobId ${job.id} time $timeMillis")
		job.labels.foreach(label=>
			RedisProvider.redisPool.zadd(queueByLabelKey_ZL(label), (timeMillis.toDouble, job.id))
		)
	}

	def retryJob(job: Job){
		persistJob(job.copy(retries = job.retries+1))
	}

	private lazy val nextJobFromQueueLua = RedisScript(s"""
		local queueByLabelPrefix = "$queueByLabelPrefix_ZL"
		local queueWorkingByLabelPrefix = "$queueWorkingByLabelPrefix_ZL"
		local queueDeferredByLabelPrefix = "$queueDeferredByLabelPrefix_ZL"
		local jobDataKey = "$jobsData_HS"
		local maxRetries = $maxRetries
		local labels = cjson.decode(ARGV[1])
		local currTimeMS = 0 + ARGV[2]
		local jobStatuses = cjson.decode('${EnumJson.toJsonMap(JobStatus).toString()}')

		local function verifyFoundJob(jobId)
			local jobData = cjson.decode(redis.call('HGET', jobDataKey ,jobId))

			--check if too many retries
			if jobData.retries >= maxRetries then
				-- defer job
				jobData.status = jobStatuses.DEFERRED
				for _i, deferLabel in ipairs(jobData.labels) do
					redis.call('ZADD', queueDeferredByLabelPrefix .. deferLabel, currTimeMS, jobData.id)
					redis.call('ZREM', queueWorkingByLabelPrefix .. deferLabel, jobData.id)
				end
				local jobDataEncoded = cjson.encode(jobData)
				redis.call('HSET', jobDataKey ,jobData.id, jobDataEncoded)
				return nil
			else
				-- looks good, accept job
				jobData.retries = jobData.retries +1
				jobData.status = jobStatuses.WORKING
				jobData.lastRunTime = currTimeMS
				for _i, _v in ipairs(jobData.labels) do
					redis.call('ZREM', queueByLabelPrefix .. _v, jobData.id)
					redis.call('ZADD', queueWorkingByLabelPrefix .. _v, currTimeMS, jobData.id)
				end
				local jobDataEncoded = cjson.encode(jobData)
				redis.call('HSET', jobDataKey ,jobData.id, jobDataEncoded)
				return jobData
			end
		end

		local function findNext(label)
			local oldJobs = redis.call('ZRANGEBYSCORE', queueWorkingByLabelPrefix .. label, 0, currTimeMS - 5 * 60 * 1000, 'LIMIT',0,1)
			if #oldJobs > 0 then
				-- try to find old job from the queue
				local oldJobId = oldJobs[1]
				local verifiedJob = verifyFoundJob(oldJobId)
				if verifiedJob then return verifiedJob
				else findNext(label) end
			else
				-- try to find a normal job for this label
				local jobIDFromQeueueTable = redis.call('ZRANGEBYSCORE', queueByLabelPrefix .. label, 0, currTimeMS, 'LIMIT',0,1)
				if #jobIDFromQeueueTable > 0 then
					local jobIDFromQeueue = jobIDFromQeueueTable[1]
					return verifyFoundJob(jobIDFromQeueue)
				end
			end
		end
		for i, label in ipairs(labels) do
			local found = findNext(label)
			if found then return cjson.encode(found) end
		end
		-- if we got this far, there are no jobs in the queue
		return nil
		"""	)

	def getNextJobFromQueue(labels: List[String]) : Option[Job] = {
		logger.info(s"getting next job from queue for labels:${labels.mkString(",")}")
		RedisProvider.blocking[RedisReply] {
			_.evalshaOrEval(nextJobFromQueueLua,Nil,Seq(Json.toJson(labels).toString(),DateTime.now(DateTimeZone.UTC).getMillis.toString))
		} match {
			case b: Bulk => b.toOptString.map(s=>Json.parse(s.toString).as[Job])
			case _ => None
		}
	}

	def ADD_JOB(state: Specialization.State, instructions: String, description:String, labels: List[JsString], inputIndices:List[JsString])(implicit ctx : ExecutionContext) : RoutableFuture = future {

			val inputKeys =  inputIndices.map(_.value)
			if (!inputKeys.filterNot(i=>state.keys.exists(i.equals)).isEmpty){
				Failure(new Exception("could not find all input values in state, missing:"
						+ inputKeys.filterNot(i=>state.keys.exists(i.equals)).mkString(",") ))
			} else {
				val inputs = inputKeys.zip(inputKeys.map(iName=> JsonFriend.jsonify(state(iName)))).toMap

				val job = Job(
					instructions,
					labels.map(_.value),
					inputs,
					description,
					JobType.AD_HOC,
					Priority.LOW,
					getNextJobId
				)

				persistJob(job)
				persistJobQueue(job,DateTime.now(DateTimeZone.UTC).getMillis)
				Success(Yield(None))
			}
	}

	private def writeJobPlan(jobId:String, schedule:String) = {
		logger.info(s"planning jobId:$jobId with schedule: $schedule")
		RedisProvider.redisPool.hset(schedulePlansKey_HS, jobId, schedule)
	}

	def PLAN(state: Specialization.State, schedule: String, jobId: String)(implicit ctx : ExecutionContext) : RoutableFuture = future {
		blocking { writeJobPlan(jobId, schedule) }
		Success(Yield(None))
	}
	
	private def queueAScheduleJob(job:Job,schedule:String) {
		val startTimeMS = job.lastRunTime.getOrElse(DateTime.now(DateTimeZone.UTC).getMillis)
		val endTimeMS = DateTime.now(DateTimeZone.UTC).plusHours(planAheadHours).getMillis
		Scron.parse(schedule, startTimeMS / 1000, endTimeMS / 1000).foreach {
			time => {
				val nextJobId = getNextJobId
				logger.info(s"queueing for parentId:${job.id} childId:$nextJobId time:${time * 1000}")
				val childJob = Job.createChildWithId(job, nextJobId)
				persistJob(childJob)
				persistJobQueue(childJob, time * 1000)
			}
		}
		logger.info(s"done queueing job ID:${job.id}")
	}

	def QUEUE(state: Specialization.State, jobId : String)(implicit ctx : ExecutionContext) : RoutableFuture = future {
		val jobOpt = getJob(jobId)
		val jobSchedule = getScheduleForJob(jobId)
		if (jobOpt.isEmpty) Failure(new Exception("could not find job for ID" + jobId))
		else if (jobSchedule.isEmpty) Failure(new Exception("could not find schedule for job ID" + jobId))
		else {
			val job = jobOpt.get.touch
      blocking {
        persistJob(job)
        queueAScheduleJob(job,jobSchedule.get)
      }
      Success(Yield(None))
		}
	}

	def queueAll(){
		logger.info("queueing all scheduled jobs")
		val scheduledJobs = getAllScheduledJobs
		if (!scheduledJobs.isEmpty){
			scheduledJobs.keys.foreach { jobId =>
				val jobOpt = getJob(jobId)
				jobOpt match {
					case Some(fetchedJob)=>
						val job = fetchedJob.touch
						persistJob(job)
						queueAScheduleJob(job,scheduledJobs(jobId))
					case None => logger.log(Level.SEVERE,s"failed to find job id $jobId")
				}

			}
		}
	}

	def QUEUE_ALL(state: Specialization.State)(implicit ctx : ExecutionContext) : RoutableFuture = future {
		queueAll()
		Success(Yield(None))
	}

}
