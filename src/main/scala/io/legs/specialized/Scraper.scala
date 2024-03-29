package io.legs.specialized

import java.io.StringReader
import java.util.logging.{Level, Logger}
import javax.xml.transform.stream.StreamSource

import io.legs.Specialization
import io.legs.Specialization.{RoutableFuture, Yield}
import io.legs.network.Communicator
import io.legs.network.simple.SimpleCommunicator
import net.sf.saxon.s9api.Processor
import org.htmlcleaner.{DomSerializer, HtmlCleaner}
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import scala.collection.immutable.::
import scala.concurrent._
import scala.util.{Failure, Success}


object SimpleScraper extends Scraper {
	val communicator = SimpleCommunicator
}


trait Scraper extends Specialization {

	private lazy val logger = Logger.getLogger(this.getClass.getSimpleName)

	def communicator: Communicator

	def FETCH(state: Specialization.State, url:String)(implicit ctx : ExecutionContext) : RoutableFuture = future {

		val res = communicator.getHtmlStr(url)
		Success(Yield(Some(res)))

	}

	def FETCH_RAW(state: Specialization.State, url:String)(implicit ctx : ExecutionContext) : RoutableFuture = future {
		try {
			val res = communicator.getUrlStr(url)
			Success(Yield(Some(res)))
		} catch {
			case e: Exception =>
				logger.log(Level.SEVERE,s"error while trying to fetch url $url",e)
				Failure(new Throwable(s"error while trying to fetch url $url",e))
		}
	}


	/*
	* Uses Jsoup extractor syntax for extraction
	* docs - http://jsoup.org/cookbook/extracting-data/selector-syntax
	* playground - http://try.jsoup.org/
	* */
	def EXTRACT_JSOUP(state: Specialization.State, inputString: String, selector: String, validator: String )(implicit ctx : ExecutionContext) : RoutableFuture = future {
		val parsed = Jsoup.parse(inputString)
		try {
			val resArr = parsed.select(selector).toArray
			val returns = resArr.map(_.asInstanceOf[Element].text()).toList
			Success(Yield(Some(returns)))
		} catch {
			case e:Exception =>
				Failure(e)
		}

	}


	def EXTRACT_XML_XPATH(state: Specialization.State, inputString: String, selector: String, validator: String ) : RoutableFuture =
		try {
			val processor = new Processor(false)
			val cleanInput = inputString.replace('&',' ')
			val doc = processor.newDocumentBuilder().build( new StreamSource( new StringReader(cleanInput) ) )
			val xpCompiler = processor.newXPathCompiler()
			val xpath = xpCompiler.compile(selector)
			val loaded = xpath.load()
			loaded.setContextItem(doc)
			val evaluated = loaded.evaluate()

			import scala.collection.JavaConverters._
			val foundItems = evaluated.iterator.asScala.map(_.getStringValue).toList

			Future.successful(Success(Yield(Some(foundItems))))
		} catch {
			case e: Exception =>
				Future.failed(e)
		}



	/*
	* Uses XPATH style for extraction
	* http://www.saxonica.com/documentation/#!functions
	* http://www.saxonica.com/documentation/#!expressions
	* */
	def EXTRACT_HTML_XPATH(state: Specialization.State, inputString: String, selector: String, validator: String )(implicit ctx : ExecutionContext) : RoutableFuture =
		try {

			val cleaner = new HtmlCleaner()
			val properties = cleaner.getProperties
			val node = cleaner.clean(inputString)
			val domSerializer = new DomSerializer(properties)
			val dom = domSerializer.createDOM(node)
			val domSource =  new javax.xml.transform.dom.DOMSource(dom)

			val processor = new Processor(false)
			val doc = processor.newDocumentBuilder().build(domSource)
			val xpCompiler = processor.newXPathCompiler()
			val xpath = xpCompiler.compile(selector)
			val loaded = xpath.load()
			loaded.setContextItem(doc)
			val evaluated = loaded.evaluate()

			import scala.collection.JavaConverters._
			val foundItems = evaluated.iterator.asScala.map(_.getStringValue).toList

			Future.successful(Success(Yield(Some(foundItems))))

		} catch {
			case e: Exception =>
				Future.failed(e)
		}

	def EXTRACT_HTML_XPATH_FIRST(state: Specialization.State, inputString: String, selector: String, validator: String )(implicit ctx : ExecutionContext) : RoutableFuture =
		EXTRACT_HTML_XPATH(state,inputString,selector,validator).map {
			case Success(Yield(Some(x::xs))) => Success(Yield(Some(x)))
			case whatever => whatever
		}

}

