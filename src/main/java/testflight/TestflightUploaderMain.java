package testflight;

import java.io.File;

public class TestflightUploaderMain {
    /**
     * Useful for testing
     */
    public static void main(String[] args) throws Exception {
        TestflightUploader uploader = new TestflightUploader();
        TestflightUploader.UploadRequest r = new TestflightUploader.UploadRequest();
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
