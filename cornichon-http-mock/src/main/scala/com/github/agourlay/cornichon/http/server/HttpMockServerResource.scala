package com.github.agourlay.cornichon.http.server

import com.github.agourlay.cornichon.core.Session
import com.github.agourlay.cornichon.dsl.{ BlockScopedResource, ResourceHandle }
import com.github.agourlay.cornichon.http.server.HttpMockServerResource.SessionKeys._
import io.circe.Json
import monix.eval.Task

case class HttpMockServerResource(interface: Option[String], label: String, portRange: Option[Range])
  extends BlockScopedResource {

  val sessionTarget: String = label
  val openingTitle: String = s"Starting HTTP mock server '$label'"
  val closingTitle: String = s"Shutting down HTTP mock server '$label'"

  def startResource() = {
    val mockRequestHandler = new MockServerRequestHandler()
    Task.fromFuture(new MockHttpServer(interface, portRange, mockRequestHandler.mockService).startServer()).map { serverCloseHandler ⇒
      new ResourceHandle {
        def resourceResults() = Task.delay(requestsResults(mockRequestHandler))

        val initialisedSession = Session.newEmpty.addValue(s"$label-url", serverCloseHandler._1)

        def stopResource() = serverCloseHandler._2.stopResource()
      }
    }
  }

  def requestsResults(mockRequestHandler: MockServerRequestHandler) = {
    val jsonRequests = mockRequestHandler.fetchRecordedRequestsAsJson()
    Session.newEmpty
      .addValue(s"$sessionTarget$receivedBodiesSuffix", Json.fromValues(jsonRequests).spaces2)
      .addValue(s"$sessionTarget$nbReceivedCallsSuffix", jsonRequests.size.toString)
  }
}

object HttpMockServerResource {
  object SessionKeys {
    val nbReceivedCallsSuffix = "-nb-received-calls"
    val receivedBodiesSuffix = "-received-bodies"
  }
}
