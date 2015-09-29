package com.github.agourlay.cornichon.dsl

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.agourlay.cornichon.CornichonFeature
import com.github.agourlay.cornichon.core._
import com.github.agourlay.cornichon.core.ExecutableStep._
import com.github.agourlay.cornichon.http._
import com.github.agourlay.cornichon.http.CornichonJson._
import com.github.fge.jsonschema.main.{ JsonSchema, JsonSchemaFactory }
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

trait HttpDsl extends Dsl {
  this: CornichonFeature ⇒

  implicit val requestTimeout: FiniteDuration = 2000 millis
  private val mapper = new ObjectMapper()
  private val http = new HttpService()

  private def urlBuilder(input: String) = {
    if (baseUrl.isEmpty) input
    else baseUrl + input
  }

  sealed trait Request { val name: String }

  sealed trait WithoutPayload extends Request {
    def apply(url: String, params: (String, String)*)(implicit headers: Seq[(String, String)] = Seq.empty) =
      effectStep(
        title = {
        val base = s"$name $url"
        if (params.isEmpty) base
        else s"$base with params ${params.mkString(", ")}"
      },
        effect =
        s ⇒ {
          val fullUrl = urlBuilder(url)
          val httpHeaders = http.parseHttpHeaders(headers)
          val x = this match {
            case GET    ⇒ http.Get(fullUrl, params, httpHeaders)(s)
            case DELETE ⇒ http.Delete(fullUrl, params, httpHeaders)(s)
          }
          x.map { case (_, session) ⇒ session }.fold(e ⇒ throw e, identity)
        }
      )
  }

  sealed trait WithPayload extends Request {
    def apply(url: String, payload: String, params: (String, String)*)(implicit headers: Seq[(String, String)] = Seq.empty) =
      effectStep(
        title = {
        val base = s"$name to $url with payload $payload"
        if (params.isEmpty) base
        else s"$base with params ${params.mkString(", ")}"
      },
        effect =
        s ⇒ {
          val fullUrl = urlBuilder(url)
          val httpHeaders = http.parseHttpHeaders(headers)
          val x = this match {
            case POST ⇒ http.Post(payload, fullUrl, params, httpHeaders)(s)
            case PUT  ⇒ http.Put(payload, fullUrl, params, httpHeaders)(s)
          }
          x.map { case (_, session) ⇒ session }.fold(e ⇒ throw e, identity)
        }
      )
  }

  sealed trait Streamed extends Request {
    def apply(url: String, takeWithin: FiniteDuration, params: (String, String)*)(implicit headers: Seq[(String, String)] = Seq.empty) =
      effectStep(
        title = {
        val base = s"$name $url"
        if (params.isEmpty) base
        else s"$base with params ${params.mkString(", ")}"
      },
        effect =
        s ⇒ {
          val fullUrl = urlBuilder(url)
          val httpHeaders = http.parseHttpHeaders(headers)
          val x = this match {
            case GET_SSE ⇒ http.GetSSE(fullUrl, takeWithin, params, httpHeaders)(s)
            case GET_WS  ⇒ ???
          }
          x.map { case (source, session) ⇒ session }.fold(e ⇒ throw e, identity)
        }
      )
  }

  case object GET extends WithoutPayload { val name = "GET" }

  case object DELETE extends WithoutPayload { val name = "DELETE" }

  case object POST extends WithPayload { val name = "POST" }

  case object PUT extends WithPayload { val name = "PUT" }

  case object GET_SSE extends Streamed { val name = "GET SSE" }

  case object GET_WS extends Streamed { val name = "GET WS" }

  def status_is(status: Int) = {
    session_contains(http.LastResponseStatusKey, status.toString, Some(s"HTTP status is $status"))
  }

  def headers_contain(headers: (String, String)*) =
    transform_assert_session(
      key = http.LastResponseHeadersKey,
      expected = s ⇒ true,
      (session, sessionHeaders) ⇒ {
        val sessionHeadersValue = sessionHeaders.split(",")
        headers.forall { case (name, value) ⇒ sessionHeadersValue.contains(s"$name${http.HeadersKeyValueDelim}$value") }
      }, Some(s"HTTP headers contain ${headers.mkString(", ")}")
    )

  def body_is[A](mapFct: JValue ⇒ JValue, expected: A) = {
    val stepTitle = s"HTTP response body with transformation is '$expected'"
    transform_assert_session(
      key = http.LastResponseBodyKey,
      expected = s ⇒ resolveAndParse(expected, s),
      (s, sessionValue) ⇒ mapFct(dslParse(sessionValue)),
      title = Some(stepTitle)
    )
  }

  def body_is[A](whiteList: Boolean = false, expected: A): ExecutableStep[JValue] = {
    val stepTitle = s"HTTP response body is '$expected' with whiteList=$whiteList"
    transform_assert_session(
      key = http.LastResponseBodyKey,
      title = Some(stepTitle),
      expected = s ⇒ resolveAndParse(expected, s),
      mapValue =
        (session, sessionValue) ⇒ {
          val expectedJvalue = resolveAndParse(expected, session)
          val sessionValueJson = dslParse(sessionValue)
          if (whiteList) {
            val Diff(changed, _, deleted) = expectedJvalue.diff(sessionValueJson)
            if (deleted != JNothing) throw new WhileListError(s"White list error - '$deleted' is not defined in object '$sessionValueJson")
            if (changed != JNothing) changed else expectedJvalue
          } else sessionValueJson
        }
    )
  }

  // TODO Cannot be parametrized?
  def body_is(expected: String, ignoring: String*): ExecutableStep[JValue] = {
    val stepTitle = titleBuilder(s"HTTP response body is '$expected'", ignoring)
    transform_assert_session(
      key = http.LastResponseBodyKey,
      title = stepTitle,
      expected = s ⇒ resolveAndParse(expected, s),
      mapValue =
      (session, sessionValue) ⇒ {
        val jsonSessionValue = dslParse(sessionValue)
        if (ignoring.isEmpty) jsonSessionValue
        else filterJsonKeys(jsonSessionValue, ignoring)
      }
    )
  }

  //TODO resolve placeholders
  def body_is[A](ordered: Boolean, expected: A, ignoring: String*): ExecutableStep[Iterable[JValue]] =
    parseJsonOrFail(expected) match {
      case expectedArray: JArray ⇒
        if (ordered)
          body_array_transform(_.arr.map(filterJsonKeys(_, ignoring)), s ⇒ expectedArray.arr, titleBuilder(s"response body is '$expected'", ignoring))
        else
          body_array_transform(s ⇒ s.arr.map(filterJsonKeys(_, ignoring)).toSet, s ⇒ expectedArray.arr.toSet, titleBuilder(s"response body array not ordered is '$expected'", ignoring))
      case _ ⇒
        throw new NotAnArrayError(expected)
    }

  private def filterJsonKeys(input: JValue, keys: Seq[String]): JValue =
    keys.foldLeft(input)((j, k) ⇒ j.removeField(_._1 == k))

  def save_from_body(extractor: JValue ⇒ JValue, target: String) =
    save_from_session(http.LastResponseBodyKey, s ⇒ extractor(dslParse(s)).values.toString, target)

  def save_from_body(args: (JValue ⇒ JValue, String)*) = {
    val inputs = args.map {
      case (e, t) ⇒ FromSessionSetter(http.LastResponseBodyKey, s ⇒ e(dslParse(s)).values.toString, t)
    }
    save_from_session(inputs)
  }

  def save_body_key(rootKey: String, target: String) =
    save_from_session(http.LastResponseBodyKey, s ⇒ (dslParse(s) \ rootKey).values.toString, target)

  def save_body_keys(args: (String, String)*) = {
    val inputs = args.map {
      case (e, t) ⇒ FromSessionSetter(http.LastResponseBodyKey, s ⇒ (dslParse(s) \ e).values.toString, t)
    }
    save_from_session(inputs)
  }

  def show_last_status = show_session(http.LastResponseStatusKey)

  def show_last_response_body = show_session(http.LastResponseBodyKey)

  def show_last_response_headers = show_session(http.LastResponseHeadersKey)

  private def titleBuilder(baseTitle: String, ignoring: Seq[String]): Option[String] =
    if (ignoring.isEmpty) Some(baseTitle)
    else Some(s"$baseTitle ignoring keys ${ignoring.map(v ⇒ s"'$v'").mkString(", ")}")

  def body_array_transform[A](mapFct: JArray ⇒ A, expected: Session ⇒ A, title: Option[String]): ExecutableStep[A] =
    transform_assert_session[A](
      title = title,
      key = http.LastResponseBodyKey,
      expected = s ⇒ expected(s),
      mapValue =
      (session, sessionValue) ⇒ {
        dslParse(sessionValue) match {
          case arr: JArray ⇒
            logger.debug(s"response_body_array_is applied to ${pretty(render(arr))}")
            mapFct(arr)
          case _ ⇒ throw new NotAnArrayError(sessionValue)
        }
      }
    )

  def response_array_size_is(size: Int) = body_array_transform(_.arr.size, s ⇒ size, Some(s"response array size is $size"))

  def response_array_contains(element: String) = body_array_transform(_.arr.contains(parse(element)), s ⇒ true, Some(s"response array contains $element"))

  def body_against_schema(schemaUrl: String) =
    transform_assert_session(
      key = http.LastResponseBodyKey,
      expected = s ⇒ Success(true),
      title = Some(s"HTTP response body is valid against JSON schema $schemaUrl"),
      mapValue =
        (session, sessionValue) ⇒ {
          val jsonNode = mapper.readTree(sessionValue)
          Try {
            loadJsonSchemaFile(schemaUrl).validate(jsonNode).isSuccess
          }
        }
    )

  private def loadJsonSchemaFile(fileLocation: String): JsonSchema =
    JsonSchemaFactory.newBuilder().freeze().getJsonSchema(fileLocation)

  private def resolveAndParse[A](input: A, session: Session): JValue =
    parseJsonOrFail(resolveInput(input)(session))

  def WithHeaders(headers: (String, String)*)(steps: ⇒ Unit)(implicit b: ScenarioBuilder) = {
    b.addStep {
      save(http.WithHeadersKey, headers.map { case (name, value) ⇒ s"$name${http.HeadersKeyValueDelim}$value" }.mkString(",")).copy(show = false)
    }
    steps
    b.addStep {
      remove(http.WithHeadersKey).copy(show = false)
    }
  }
}