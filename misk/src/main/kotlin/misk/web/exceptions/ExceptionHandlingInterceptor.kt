package misk.web.exceptions

import com.google.common.util.concurrent.UncheckedExecutionException
import com.squareup.wire.GrpcStatus
import com.squareup.wire.ProtoAdapter
import misk.Action
import misk.exceptions.UnauthenticatedException
import misk.exceptions.UnauthorizedException
import misk.grpc.GrpcMessageSink
import misk.web.DispatchMechanism
import misk.web.HttpCall
import misk.web.NetworkChain
import misk.web.NetworkInterceptor
import misk.web.Response
import misk.web.ResponseBody
import misk.web.mediatype.MediaTypes
import misk.web.toResponseBody
import okhttp3.Headers.Companion.toHeaders
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import wisp.logging.getLogger
import wisp.logging.log
import java.lang.reflect.InvocationTargetException
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * Converts and logs application and component level dispatch exceptions into the appropriate
 * response format. Allows application and component code to control how exceptions are
 * represented to clients; for example by setting the status code appropriately, or by returning
 * a specialized response format specific to the error. Components can control how exceptions are
 * mapped by installing [ExceptionMapper] via the [ExceptionMapperModule]
 *
 * TODO(isabel): Set the response body in a ThreadLocal to log in [RequestLoggingInterceptor]
 */
class ExceptionHandlingInterceptor(
  private val actionName: String,
  private val mapperResolver: ExceptionMapperResolver
) : NetworkInterceptor {

  override fun intercept(chain: NetworkChain) {
    try {
      chain.proceed(chain.httpCall)
    } catch (th: Throwable) {
      val response = toResponse(th)
      chain.httpCall.statusCode = response.statusCode
      if (chain.httpCall.dispatchMechanism == DispatchMechanism.GRPC) {
        sendGrpcFailure(chain.httpCall, response)
      } else {
        sendHttpFailure(chain.httpCall, response)
      }
    }
  }

  private fun sendHttpFailure(httpCall: HttpCall, response: Response<*>) {
    httpCall.takeResponseBody()?.use { sink ->
      httpCall.addResponseHeaders(response.headers)
      (response.body as ResponseBody).writeTo(sink)
    }
  }

  /**
   * Borrow behavior from [GrpcFeatureBinding] to send a gRPC error with an HTTP 200 status code.
   * This is weird but it's how gRPC clients work.
   *
   * One thing to note is for our metrics we want to pretend that the HTTP code is what we sent.
   * Otherwise gRPC requests that crashed and yielded an HTTP 200 code will confuse operators.
   */
  private fun sendGrpcFailure(httpCall: HttpCall, response: Response<*>) {
    httpCall.setStatusCodes(httpCall.statusCode, 200)
    httpCall.requireTrailers()
    httpCall.setResponseHeader("grpc-encoding", "identity")
    httpCall.setResponseHeader("Content-Type", MediaTypes.APPLICATION_GRPC)
    httpCall.setResponseTrailer(
      "grpc-status",
      toGrpcStatus(response.statusCode).code.toString()
    )
    httpCall.setResponseTrailer("grpc-message", this.grpcMessage(response))
    httpCall.takeResponseBody()?.use { responseBody: BufferedSink ->
      GrpcMessageSink(responseBody, ProtoAdapter.BYTES, grpcEncoding = "identity")
        .use { messageSink ->
          messageSink.write(ByteString.EMPTY)
        }
    }
  }

  private fun grpcMessage(response: Response<*>): String {
    val buffer = Buffer()
    (response.body as ResponseBody).writeTo(buffer)
    return buffer.readUtf8()
  }

  /** https://grpc.github.io/grpc/core/md_doc_http-grpc-status-mapping.html */
  private fun toGrpcStatus(statusCode: Int): GrpcStatus {
    return when (statusCode) {
      400 -> GrpcStatus.INTERNAL
      401 -> GrpcStatus.UNAUTHENTICATED
      403 -> GrpcStatus.PERMISSION_DENIED
      404 -> GrpcStatus.UNIMPLEMENTED
      429 -> GrpcStatus.UNAVAILABLE
      502 -> GrpcStatus.UNAVAILABLE
      503 -> GrpcStatus.UNAVAILABLE
      504 -> GrpcStatus.UNAVAILABLE
      else -> GrpcStatus.UNKNOWN
    }
  }

  private fun toResponse(th: Throwable): Response<*> = when (th) {
    is UnauthenticatedException -> UNAUTHENTICATED_RESPONSE
    is UnauthorizedException -> UNAUTHORIZED_RESPONSE
    is InvocationTargetException -> toResponse(th.targetException)
    is UncheckedExecutionException -> toResponse(th.cause!!)
    else -> mapperResolver.mapperFor(th)?.let {
      log.log(it.loggingLevel(th), th) { "exception dispatching to $actionName" }
      it.toResponse(th)
    } ?: toInternalServerError(th)
  }

  private fun toInternalServerError(th: Throwable): Response<*> {
    log.error(th) { "unexpected error dispatching to $actionName" }
    return INTERNAL_SERVER_ERROR_RESPONSE
  }

  class Factory @Inject internal constructor(
    private val mapperResolver: ExceptionMapperResolver
  ) : NetworkInterceptor.Factory {
    override fun create(action: Action) = ExceptionHandlingInterceptor(action.name, mapperResolver)
  }

  private companion object {
    val log = getLogger<ExceptionHandlingInterceptor>()

    val INTERNAL_SERVER_ERROR_RESPONSE = Response(
      "internal server error".toResponseBody(),
      listOf("Content-Type" to MediaTypes.TEXT_PLAIN_UTF8).toMap().toHeaders(),
      HttpURLConnection.HTTP_INTERNAL_ERROR
    )

    val UNAUTHENTICATED_RESPONSE = Response(
      "unauthenticated".toResponseBody(),
      listOf("Content-Type" to MediaTypes.TEXT_PLAIN_UTF8).toMap().toHeaders(),
      HttpURLConnection.HTTP_UNAUTHORIZED
    )

    val UNAUTHORIZED_RESPONSE = Response(
      "unauthorized".toResponseBody(),
      listOf("Content-Type" to MediaTypes.TEXT_PLAIN_UTF8).toMap().toHeaders(),
      HttpURLConnection.HTTP_FORBIDDEN
    )
  }
}
