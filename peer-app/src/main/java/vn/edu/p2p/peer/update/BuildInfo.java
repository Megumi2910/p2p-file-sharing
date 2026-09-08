package vn.edu.p2p.peer.update;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

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
    public static final int MAX_BUILD_PROPERTIES_BYTES = 16384;

    public boolean canInstall() {
        return !isDevelopment
                && version != null
                && publicKey != null
                && installerProtocol == EXPECTED_INSTALLER_PROTOCOL
                && DEFAULT_REPOSITORY.equals(repository);
    }

    public String displayVersion() {
        if (isDevelopment || version == null) {
            return "Development build";
        }
        return version.toString();
    }

    public static Properties loadBoundedProperties(InputStream in, int maxBytes) throws IOException {
        byte[] bytes = in.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IOException("Properties stream exceeded maximum allowed size of " + maxBytes + " bytes");
        }

        Properties props = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate property key in properties: " + key);
                }
                return super.put(key, value);
            }
        };
        try (InputStream inStream = new ByteArrayInputStream(bytes)) {
            props.load(inStream);
        } catch (IllegalArgumentException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        return props;
    }

    public static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/vn/edu/p2p/peer/update/build.properties")) {
            if (in == null) {
                return developmentDefault();
            }
            Properties props = loadBoundedProperties(in, MAX_BUILD_PROPERTIES_BYTES);
            return fromProperties(props);
        } catch (Exception ex) {
            return developmentDefault();
        }
    }

    public static BuildInfo readJar(Path jar) throws IOException {
        Objects.requireNonNull(jar, "jar path cannot be null");
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Set<String> seenEntries = new HashSet<>();
            int manifestCount = 0;
            int buildPropsCount = 0;

            Enumeration<JarEntry> en = jarFile.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                String name = entry.getName();
                if (!seenEntries.add(name)) {
                    throw new SecurityException("Duplicate JAR entry: " + name);
                }
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                    manifestCount++;
                }
                if ("vn/edu/p2p/peer/update/build.properties".equals(name)) {
                    buildPropsCount++;
                }
            }

            if (manifestCount != 1) {
                throw new SecurityException("Expected exactly one manifest in JAR, found: " + manifestCount);
            }
            if (buildPropsCount != 1) {
                throw new SecurityException("Expected exactly one embedded build.properties in JAR, found: " + buildPropsCount);
            }

            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                throw new SecurityException("JAR missing META-INF/MANIFEST.MF: " + jar);
            }
            Attributes mainAttrs = manifest.getMainAttributes();
            String mainClass = mainAttrs.getValue(Attributes.Name.MAIN_CLASS);
            if (!"vn.edu.p2p.peer.PeerApplication".equals(mainClass)) {
                throw new SecurityException("JAR Main-Class mismatch: expected vn.edu.p2p.peer.PeerApplication but got " + mainClass);
            }

            JarEntry buildEntry = jarFile.getJarEntry("vn/edu/p2p/peer/update/build.properties");
            if (buildEntry.getSize() > MAX_BUILD_PROPERTIES_BYTES) {
                throw new SecurityException("JAR embedded build.properties exceeds 16 KiB: " + buildEntry.getSize());
            }

            Properties props;
            try (InputStream in = jarFile.getInputStream(buildEntry)) {
                props = loadBoundedProperties(in, MAX_BUILD_PROPERTIES_BYTES);
            }

            return fromProperties(props);
        }
    }

    public static BuildInfo fromProperties(Properties props) {
        String versionStr = props.getProperty("version");
        String repoStr = props.getProperty("repository");
        String pubKeyStr = props.getProperty("publicKey");
        String protoStr = props.getProperty("installerProtocol");

        boolean dev = false;
        ClientVersion parsedVersion = null;
        if (versionStr == null || versionStr.isBlank() || versionStr.contains("${") || versionStr.contains("-dev")) {
            dev = true;
            versionStr = versionStr == null ? "" : versionStr.trim();
        } else {
            versionStr = versionStr.trim();
            try {
                parsedVersion = ClientVersion.parse(versionStr);
            } catch (Exception ex) {
                dev = true;
            }
        }

        if (repoStr == null || repoStr.isBlank() || repoStr.contains("${")) {
            dev = true;
            repoStr = repoStr == null ? "" : repoStr.trim();
        } else {
            repoStr = repoStr.trim();
            if (!DEFAULT_REPOSITORY.equals(repoStr)) {
                dev = true;
            }
        }

        int protocol = 0;
        if (protoStr == null || protoStr.isBlank() || protoStr.contains("${")) {
            dev = true;
        } else {
            protoStr = protoStr.trim();
            if (!"1".equals(protoStr)) {
                dev = true;
            } else {
                protocol = EXPECTED_INSTALLER_PROTOCOL;
            }
        }

        PublicKey pubKey = null;
        if (pubKeyStr == null || pubKeyStr.isBlank() || pubKeyStr.contains("${")) {
            pubKeyStr = pubKeyStr == null ? "" : pubKeyStr.trim();
        } else {
            pubKeyStr = pubKeyStr.trim();
            try {
                byte[] keyBytes = Base64.getDecoder().decode(pubKeyStr);
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                pubKey = kf.generatePublic(new X509EncodedKeySpec(keyBytes));
            } catch (Exception ex) {
                pubKey = null;
                dev = true;
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
