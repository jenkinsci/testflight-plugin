package testflight;

import hudson.model.BuildListener;
import hudson.remoting.Callable;

import java.io.File;
import java.io.Serializable;

/**
 * Code for sending a build to TestFlight which can run on a master or slave.
 */
public class TestflightRemoteRecorder implements Callable<Object, Throwable>, Serializable {
    final private boolean pathSpecified;
    final private boolean dsymPathSpecified;
    final private TestflightUploader.UploadRequest uploadRequest;
    final private BuildListener listener;

    public TestflightRemoteRecorder(boolean pathSpecified, boolean dsymPathSpecified, TestflightUploader.UploadRequest uploadRequest, BuildListener listener) {
        this.pathSpecified = pathSpecified;
        this.dsymPathSpecified = dsymPathSpecified;
        this.uploadRequest = uploadRequest;
        this.listener = listener;
    }

    public Object call() throws Throwable {
        uploadRequest.file = identifyFile(pathSpecified, uploadRequest.filePath, ".ipa");
        uploadRequest.dsymFile = identifyFile(dsymPathSpecified, uploadRequest.dsymPath, "-dSYM.zip");

        listener.getLogger().println(uploadRequest.file);

        TestflightUploader uploader = new TestflightUploader();
        return uploader.upload(uploadRequest);
    }

    private File identifyFile(boolean specified, String path, String endsWith) {
        if (specified) {
            return new File(path);
        }

        File workspaceDir = new File(path);
        File possibleDsym = TestflightRemoteRecorder.findFile(workspaceDir, endsWith);
        return possibleDsym != null ? possibleDsym : null;
    }

	private static File findFile(File root, String endsWith) {
		for (File file : root.listFiles()) {
            if (file.isDirectory()) {
                File fileResult = findFile(file, endsWith);
                if (fileResult != null) {
                    return fileResult;
                }
            }
            else if (file.getName().endsWith(endsWith)) {
                return file;
            }
        }
        return null;
	}
}
