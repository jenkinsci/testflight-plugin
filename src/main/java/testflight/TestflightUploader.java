package testflight;

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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Map;
import java.util.Scanner;

/**
 * A testflight uploader
 */
public class TestflightUploader {
    static class UploadRequest {
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
    }

    public Map upload(UploadRequest ur) throws IOException, org.json.simple.parser.ParseException {

        DefaultHttpClient httpClient = new DefaultHttpClient();

        // Configure the proxy if necessary
        if(ur.proxyHost!=null && !ur.proxyHost.isEmpty() && ur.proxyPort>0) {
            Credentials cred = null;
            if(ur.proxyUser!=null && !ur.proxyUser.isEmpty())
                cred = new UsernamePasswordCredentials(ur.proxyUser, ur.proxyPass);

            httpClient.getCredentialsProvider().setCredentials(new AuthScope(ur.proxyHost, ur.proxyPort),cred);
            HttpHost proxy = new HttpHost(ur.proxyHost, ur.proxyPort);
            httpClient.getParams().setParameter( ConnRoutePNames.DEFAULT_PROXY, proxy);
        }

        HttpHost targetHost = new HttpHost("testflightapp.com");
        HttpPost httpPost = new HttpPost("/api/builds.json");
        FileBody fileBody = new FileBody(ur.file);

        MultipartEntity entity = new MultipartEntity();
        entity.addPart("api_token", new StringBody(ur.apiToken));
        entity.addPart("team_token", new StringBody(ur.teamToken));
        entity.addPart("notes", new StringBody(ur.buildNotes));
        entity.addPart("file", fileBody);

        if (ur.dsymFile != null) {
            FileBody dsymFileBody = new FileBody(ur.dsymFile);
            entity.addPart("dsym", dsymFileBody);
        }

        if (ur.lists.length() > 0)
            entity.addPart("distribution_lists", new StringBody(ur.lists));
        entity.addPart("notify", new StringBody(ur.notifyTeam ? "True" : "False"));
        entity.addPart("replace", new StringBody(ur.replace ? "True" : "False"));
        httpPost.setEntity(entity);

        HttpResponse response = httpClient.execute(targetHost,httpPost);
        HttpEntity resEntity = response.getEntity();

        InputStream is = resEntity.getContent();

        // Improved error handling.
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != 200) {
            String responseBody = new Scanner(is).useDelimiter("\\A").next();
            throw new UploadException(statusCode, responseBody, response);
        }

        JSONParser parser = new JSONParser();

        return (Map)parser.parse(new BufferedReader(new InputStreamReader(is)));
    }

    /**  Useful for testing */
    public static void main(String[] args) throws Exception {
        TestflightUploader uploader = new TestflightUploader();
        UploadRequest r = new UploadRequest();
        r.apiToken = args[0];
        r.teamToken = args[1];
        r.buildNotes = args[2];
        File file = new File(args[3]);
        r.file = file;
        r.dsymFile = null;
        r.notifyTeam = true;
        r.replace = true;
        r.lists = args[4];

        uploader.upload(r);
    }
}
