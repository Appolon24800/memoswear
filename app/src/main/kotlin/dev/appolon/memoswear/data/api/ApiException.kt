package dev.appolon.memoswear.data.api

import java.io.IOException

/** An error returned by the Memos API. */
class ApiException(
    val code: String,
    override val message: String,
    val httpStatus: Int,
) : IOException(message) {
    val isUnauthenticated: Boolean get() = code == "unauthenticated" || code == "16"
    val isPermissionDenied: Boolean get() = code == "permission_denied" || code == "7"
    val isNotFound: Boolean get() = code == "not_found" || code == "5"
    val isInvalidArgument: Boolean get() = code == "invalid_argument" || code == "3"
    val isRateLimited: Boolean get() = httpStatus == 429 || code == "resource_exhausted" || code == "8"

    /** Errors that a later attempt can succeed on, unlike a rejected request. */
    val isRetryable: Boolean get() = isRateLimited || httpStatus >= 500 || httpStatus == 408 || code == "bad_response"

    companion object {
        /** Builds the error for a response that carried no API error document. */
        fun http(status: Int): ApiException = ApiException("http_$status", "Server returned $status", status)
    }
}
