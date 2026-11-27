package dev.appolon.memoswear.core

import dev.appolon.memoswear.data.SignInException
import dev.appolon.memoswear.data.api.ApiException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns failures into one short line for the watch screen. */
object UserMessages {
    fun forThrowable(error: Throwable): String = when (error) {
        is SignInException -> error.message ?: "Sign-in failed"
        is ApiException -> forApi(error)
        is SSLException -> "Secure connection failed"
        is SocketTimeoutException, is InterruptedIOException -> "The server took too long to answer"
        is UnknownHostException, is ConnectException -> "Can't reach the server"
        is IOException -> error.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
        else -> "Something went wrong"
    }

    fun forApi(error: ApiException): String = when {
        error.isUnauthenticated -> "Signed out"
        error.isPermissionDenied -> "Not allowed"
        error.isNotFound -> "Not found"
        error.isRateLimited -> "Too many requests, try again later"
        error.isInvalidArgument -> error.message.replaceFirstChar { it.uppercase() }
        else -> "Server error (${error.httpStatus})"
    }

    /** True when the failure means there is no usable connection right now. */
    fun isOffline(error: Throwable): Boolean = error is IOException && error !is ApiException && error !is SignInException
}
