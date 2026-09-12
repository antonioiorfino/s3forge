package it.iorfino.s3forge.model;

/**
 * Enumeration of the S3-compatible error codes returned by S3Forge.
 *
 * <p>Each constant bundles the AWS error code, a human-readable message and
 * the corresponding HTTP status code, so handlers can emit a well-formed
 * {@code <Error>} XML document with a single call.</p>
 *
 * @since 0.1.0
 */
public enum S3Error {

    /**
     * The specified bucket does not exist.
     */
    NO_SUCH_BUCKET("NoSuchBucket",
        "The specified bucket does not exist.", 404),

    /**
     * The specified key does not exist.
     */
    NO_SUCH_KEY("NoSuchKey",
        "The specified key does not exist.", 404),

    /**
     * The bucket already exists and is owned by the caller.
     */
    BUCKET_ALREADY_OWNED_BY_YOU("BucketAlreadyOwnedByYou",
        "Your previous request to create the named bucket succeeded and you already own it.", 409),

    /**
     * The bucket cannot be deleted because it is not empty.
     */
    BUCKET_NOT_EMPTY("BucketNotEmpty",
        "The bucket you tried to delete is not empty.", 409),

    /**
     * The bucket name does not conform to S3 naming rules.
     */
    INVALID_BUCKET_NAME("InvalidBucketName",
        "The specified bucket is not valid.", 400),

    /**
     * The Content-MD5 header did not match the computed digest.
     */
    BAD_DIGEST("BadDigest",
        "The Content-MD5 you specified did not match what we received.", 400),

    /** A request parameter is malformed or missing. */
    INVALID_ARGUMENT("InvalidArgument",
        "Invalid argument.", 400),

    /** The copy source and destination are identical and no REPLACE directive was given. */
    INVALID_REQUEST_COPY_SELF("InvalidRequest",
        "This copy request is illegal because it is trying to copy an object to itself without changing the object's metadata, storage class, website redirect location or encryption attributes.", 400),

    /** The request body does not conform to the expected XML schema. */
    MALFORMED_XML("MalformedXML",
        "The XML you provided was not well-formed or did not validate against our published schema.", 400),

    /** Too many keys were provided in a single DeleteObjects request. */
    TOO_MANY_KEYS("MalformedXML",
        "The XML you provided was not well-formed or did not validate against our published schema.", 400),

    /** The requested range cannot be satisfied. */
    INVALID_RANGE("InvalidRange",
        "The requested range is not satisfiable", 416),

    /** The specified multipart upload does not exist. */
    NO_SUCH_UPLOAD("NoSuchUpload",
        "The specified upload does not exist. The upload ID may be invalid, or the upload may have been aborted or completed.", 404),

    /** One of the parts listed in CompleteMultipartUpload is missing. */
    INVALID_PART("InvalidPart",
        "One or more of the specified parts could not be found. The part may not have been uploaded, or the specified entity tag may not match the part's entity tag.", 400),

    /** Parts were provided out of order or with invalid part numbers. */
    INVALID_PART_ORDER("InvalidPartOrder",
        "The list of parts was not in ascending order. Parts must be ordered by part number.", 400),

    /**
     * Generic malformed request.
     */
    INVALID_REQUEST("InvalidRequest",
        "Invalid request.", 400),

    /**
     * Unexpected server-side error.
     */
    INTERNAL_ERROR("InternalError",
        "We encountered an internal error.", 500);

    private final String code;
    private final String message;
    private final int httpStatus;

    S3Error(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the AWS error code (e.g. {@code "NoSuchBucket"}).
     *
     * @return the error code; never {@code null}
     */
    public String code() {
        return code;
    }

    /**
     * Returns the human-readable error message.
     *
     * @return the message; never {@code null}
     */
    public String message() {
        return message;
    }

    /**
     * Returns the HTTP status code associated with this error.
     *
     * @return the HTTP status
     */
    public int httpStatus() {
        return httpStatus;
    }
}
