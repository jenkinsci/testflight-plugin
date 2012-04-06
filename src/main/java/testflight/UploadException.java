package testflight;

import org.apache.http.HttpResponse;

public class UploadException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;
    private final HttpResponse response;

    public UploadException(int statusCode, String responseBody, HttpResponse response) {
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.response = response;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public HttpResponse getResponse() {
        return response;
    }
}
