package testflight;


import java.io.*;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.simple.parser.JSONParser;

/**
 * A testflight uploader
 */
public class TestflightUploader implements Serializable {
    static interface Logger {
        void logDebug(String message);
    }

    static class UploadRequest implements Serializable {
        String filePaths;
        String dsymPath;
        String apiToken;
        String teamToken;
        Boolean notifyTeam;
        String buildNotes;
        File file;
        File dsymFile;
        String lists;
        Boolean replace;
        String proxyHost;
        String proxyUser;
        String proxyPass;
        int proxyPort;
        List<Pattern> noProxyHostPatterns;
        Boolean debug;
        static final String apiDomain = "testflightapp.com";
        static final String httpPost = "/api/builds.json";
        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .append("filePaths", filePaths)
                    .append("dsymPath", dsymPath)
                    .append("apiToken", "********")
                    .append("teamToken", "********")
                    .append("notifyTeam", notifyTeam)
                    .append("buildNotes", buildNotes)
                    .append("file", file)
                    .append("dsymFile", dsymFile)
                    .append("lists", lists)
                    .append("replace", replace)
                    .append("proxyHost", proxyHost)
                    .append("proxyUser", proxyUser)
                    .append("proxyPass", "********")
                    .append("proxyPort", proxyPort)
                    .append("debug", debug)
                    .toString();
        }

        static UploadRequest copy(UploadRequest r) {
            UploadRequest r2 = new UploadRequest();
            r2.filePaths = r.filePaths;
            r2.dsymPath = r.dsymPath;
            r2.apiToken = r.apiToken;
            r2.teamToken = r.teamToken;
            r2.notifyTeam = r.notifyTeam;
            r2.buildNotes = r.buildNotes;
            r2.file = r.file;
            r2.dsymFile = r.dsymFile;
            r2.lists = r.lists;
            r2.replace = r.replace;
            r2.proxyHost = r.proxyHost;
            r2.proxyUser = r.proxyUser;
            r2.proxyPort = r.proxyPort;
            r2.proxyPass = r.proxyPass;
            r2.noProxyHostPatterns = r.noProxyHostPatterns;
            r2.debug = r.debug;

            return r2;
        }
    }

    private Logger logger = null;

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    private boolean ignoreProxy(UploadRequest ur){
        for (Pattern pattern : ur.noProxyHostPatterns){
            if(pattern.matcher(UploadRequest.apiDomain).matches()){
                logDebug("Pattern '" + pattern.toString() + "' matches '" + UploadRequest.apiDomain +"'");
                return true;
            }
            logDebug("Pattern '" + pattern.toString() + "' does not match '" + UploadRequest.apiDomain +"'");
        }
        return false;
    }
    
    public Map upload(UploadRequest ur) throws IOException, org.json.simple.parser.ParseException {
        DefaultHttpClient httpClient = new DefaultHttpClient();

        if(ignoreProxy(ur)){
            //clear proxy settings to provide correct debug messages
            ur.proxyHost = "<ignored>";
            ur.proxyPort = 0;
            ur.proxyUser = "<ignored>";
            ur.proxyPass = "<ignored>";
        }else{ 
            // Configure the proxy if necessary
            if (ur.proxyHost != null && !ur.proxyHost.isEmpty() && ur.proxyPort > 0) {
                Credentials cred = null;
                if (ur.proxyUser != null && !ur.proxyUser.isEmpty())
                    cred = new UsernamePasswordCredentials(ur.proxyUser, ur.proxyPass);

                httpClient.getCredentialsProvider().setCredentials(new AuthScope(ur.proxyHost, ur.proxyPort), cred);
                HttpHost proxy = new HttpHost(ur.proxyHost, ur.proxyPort);
                httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
            }
        }

        HttpHost targetHost = new HttpHost(UploadRequest.apiDomain);
        HttpPost httpPost = new HttpPost(UploadRequest.httpPost);
        FileBody fileBody = new FileBody(ur.file);

        MultipartEntity entity = new MultipartEntity();
        entity.addPart("api_token", new StringBody(ur.apiToken));
        entity.addPart("team_token", new StringBody(ur.teamToken));
        entity.addPart("notes", new StringBody(ur.buildNotes, "text/plain", Charset.forName("UTF-8")));
        entity.addPart("file", fileBody);

        if (ur.dsymFile != null) {
            FileBody dsymFileBody = new FileBody(ur.dsymFile);
            entity.addPart("dsym", dsymFileBody);
        }

        if (ur.lists.length() > 0)
            entity.addPart("distribution_lists", new StringBody(ur.lists));
        entity.addPart("notify", new StringBody(ur.notifyTeam ? "True" : "False"));
        if (ur.replace)
            entity.addPart("replace", new StringBody("True"));
        httpPost.setEntity(entity);

        logDebug("POST Request: " + ur);

        HttpResponse response = httpClient.execute(targetHost, httpPost);
        HttpEntity resEntity = response.getEntity();

        InputStream is = resEntity.getContent();

        // Improved error handling.
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != 200) {
            String responseBody = new Scanner(is).useDelimiter("\\A").next();
            throw new UploadException(statusCode, responseBody, response);
        }

        StringWriter writer = new StringWriter();
        IOUtils.copy(is, writer, "UTF-8");
        String json = writer.toString();

        logDebug("POST Answer: " + json);

        JSONParser parser = new JSONParser();

        return (Map) parser.parse(json);
    }

    private void logDebug(String message) {
        if (logger != null) {
            logger.logDebug(message);
        }
    }
}
