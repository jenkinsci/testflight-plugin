package testflight;

import org.kohsuke.stapler.DataBoundConstructor;

public class TestflightTeam {

	private String tokenPairName;
	private String filePath;
	private String dsymPath;
	
	@DataBoundConstructor
	public TestflightTeam(String tokenPairName, String filePath, String dsymPath) {
		super();
		this.tokenPairName = tokenPairName;
		this.filePath = filePath;
		this.dsymPath = dsymPath;
	}

	public String getTokenPairName() {
		return tokenPairName;
	}

	public void setTokenPairName(String tokenPairName) {
		this.tokenPairName = tokenPairName;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public String getDsymPath() {
		return dsymPath;
	}

	public void setDsymPath(String dsymPath) {
		this.dsymPath = dsymPath;
	}
}
