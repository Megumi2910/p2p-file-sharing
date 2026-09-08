package vn.edu.p2p.peer.update;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

public record BuildInfo(
        String rawVersion,
        ClientVersion version,
        String repository,
        String rawPublicKey,
        PublicKey publicKey,
        int installerProtocol,
        boolean isDevelopment
) {

    public static final String DEFAULT_REPOSITORY = "Megumi2910/p2p-file-sharing";
    public static final int EXPECTED_INSTALLER_PROTOCOL = 1;

    public boolean canInstall() {
        return !isDevelopment && version != null && publicKey != null && installerProtocol == EXPECTED_INSTALLER_PROTOCOL;
    }

    public String displayVersion() {
        if (isDevelopment || version == null) {
            return "Development build";
        }
        return version.toString();
    }

    public static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/vn/edu/p2p/peer/update/build.properties")) {
            if (in == null) {
                return developmentDefault();
            }
            Properties props = new Properties();
            props.load(in);
            return fromProperties(props);
        } catch (Exception ex) {
            return developmentDefault();
        }
    }

    public static BuildInfo fromProperties(Properties props) {
        String versionStr = props.getProperty("version", props.getProperty("client.version", "")).trim();
        String repoStr = props.getProperty("repository", props.getProperty("client.repository", DEFAULT_REPOSITORY)).trim();
        String pubKeyStr = props.getProperty("publicKey", props.getProperty("client.publicKey", "")).trim();
        String protoStr = props.getProperty("installerProtocol", props.getProperty("client.installerProtocol", "1")).trim();

        if (repoStr.startsWith("${") || repoStr.isBlank()) {
            repoStr = DEFAULT_REPOSITORY;
        }

        int protocol = EXPECTED_INSTALLER_PROTOCOL;
        try {
            protocol = Integer.parseInt(protoStr);
        } catch (NumberFormatException ignored) {
        }

        boolean dev = false;
        ClientVersion parsedVersion = null;
        if (versionStr.isBlank() || versionStr.startsWith("${") || versionStr.contains("-dev")) {
            dev = true;
        } else {
            try {
                parsedVersion = ClientVersion.parse(versionStr);
            } catch (Exception ex) {
                dev = true;
            }
        }

        PublicKey pubKey = null;
        if (!pubKeyStr.isBlank() && !pubKeyStr.startsWith("${")) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(pubKeyStr);
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                pubKey = kf.generatePublic(new X509EncodedKeySpec(keyBytes));
            } catch (Exception ex) {
                pubKey = null;
            }
        }

        return new BuildInfo(
                versionStr,
                parsedVersion,
                repoStr,
                pubKeyStr,
                pubKey,
                protocol,
                dev
        );
    }

    public static BuildInfo developmentDefault() {
        return new BuildInfo(
                "0.0.0-dev",
                null,
                DEFAULT_REPOSITORY,
                "",
                null,
                EXPECTED_INSTALLER_PROTOCOL,
                true
        );
    }
}
