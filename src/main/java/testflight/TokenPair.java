package testflight;

import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

public class TokenPair {
    private String tokenPairName;
    private Secret apiToken;
    private Secret teamToken;

    public TokenPair() {
    }

    @DataBoundConstructor
    public TokenPair(String tokenPairName, Secret apiToken, Secret teamToken) {
        this.tokenPairName = tokenPairName;
        this.apiToken = apiToken;
        this.teamToken = teamToken;
    }

    public String getTokenPairName() {
        return tokenPairName;
    }

    public void setTokenPairName(String tokenPairName) {
        this.tokenPairName = tokenPairName;
    }

    public Secret getApiToken() {
        return apiToken;
    }

    public void setApiToken(Secret apiToken) {
        this.apiToken = apiToken;
    }

    public Secret getTeamToken() {
        return teamToken;
    }

    public void setTeamToken(Secret teamToken) {
        this.teamToken = teamToken;
    }
}
