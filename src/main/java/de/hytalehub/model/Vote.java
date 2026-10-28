package de.hytalehub.model;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a vote received from a voting service.
 *
 * Reference: Votifier v2 protocol
 * https://github.com/NuVotifier/go-votifier/blob/master/protocol_v2.go
 */
public class Vote {

    @SerializedName("serviceName")
    private String serviceName;

    @SerializedName("username")
    private String username;

    @SerializedName("timestamp")
    private long timestamp;

    public Vote(String serviceName, String username, long timestamp) {
        this.serviceName = serviceName;
        this.username = username;
        this.timestamp = timestamp;
    }

    public Vote() {
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "Vote{" +
            "serviceName='" + serviceName + '\'' +
            ", username='" + username + '\'' +
            ", timestamp=" + timestamp +
            '}';
    }
}
