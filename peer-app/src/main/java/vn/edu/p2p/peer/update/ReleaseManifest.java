package vn.edu.p2p.peer.update;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record ReleaseManifest(
        int schema,
        String repository,
        ClientVersion version,
        String artifact,
        long size,
        String sha256,
        int minJava,
        int installerProtocol,
        BundleInfo bundle
) {

    public static final int MAX_MANIFEST_BYTES = 16384;
    public static final int EXPECTED_SIGNATURE_BYTES = 64;
    public static final long MAX_ARTIFACT_BYTES = 128L * 1024 * 1024;
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public record BundleInfo(String name, long size, String sha256) {
        public BundleInfo {
            Objects.requireNonNull(name, "bundle name cannot be null");
            Objects.requireNonNull(sha256, "bundle sha256 cannot be null");
            if (size <= 0 || size > MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("Invalid bundle size: " + size);
            }
            if (!SHA256_PATTERN.matcher(sha256).matches()) {
                throw new IllegalArgumentException("Invalid bundle sha256 format: " + sha256);
            }
        }
    }

    public ReleaseManifest {
        Objects.requireNonNull(repository, "repository cannot be null");
        Objects.requireNonNull(version, "version cannot be null");
        Objects.requireNonNull(artifact, "artifact cannot be null");
        Objects.requireNonNull(sha256, "sha256 cannot be null");
        Objects.requireNonNull(bundle, "bundle cannot be null");

        if (schema != 1) {
            throw new IllegalArgumentException("Unsupported schema: " + schema);
        }
        if (!"peer-app.jar".equals(artifact)) {
            throw new IllegalArgumentException("Unsupported artifact name: " + artifact);
        }
        if (size <= 0 || size > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Invalid artifact size: " + size);
        }
        if (!SHA256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Invalid artifact sha256 format: " + sha256);
        }
        if (minJava != 21) {
            throw new IllegalArgumentException("Unsupported minJava requirement: " + minJava);
        }
        if (installerProtocol != 1) {
            throw new IllegalArgumentException("Unsupported installerProtocol: " + installerProtocol);
        }
        String expectedBundleName = "p2p-client-" + version + ".zip";
        if (!expectedBundleName.equals(bundle.name())) {
            throw new IllegalArgumentException("Bundle name mismatch: expected " + expectedBundleName + " but got " + bundle.name());
        }
    }

    public static ReleaseManifest parseAndVerify(
            byte[] manifestBytes,
            byte[] signatureBytes,
            PublicKey publicKey,
            String expectedRepository
    ) throws Exception {
        Objects.requireNonNull(manifestBytes, "manifestBytes cannot be null");
        Objects.requireNonNull(signatureBytes, "signatureBytes cannot be null");
        Objects.requireNonNull(publicKey, "publicKey cannot be null");
        Objects.requireNonNull(expectedRepository, "expectedRepository cannot be null");

        if (manifestBytes.length == 0 || manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("Manifest size out of bounds: " + manifestBytes.length);
        }
        if (signatureBytes.length != EXPECTED_SIGNATURE_BYTES) {
            throw new SecurityException("Invalid signature length: " + signatureBytes.length + " (expected " + EXPECTED_SIGNATURE_BYTES + ")");
        }

        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(publicKey);
        sig.update(manifestBytes);
        if (!sig.verify(signatureBytes)) {
            throw new SecurityException("Ed25519 manifest signature verification failed");
        }

        return parseStrict(manifestBytes, expectedRepository);
    }

    public static ReleaseManifest parseStrict(byte[] manifestBytes, String expectedRepository) throws IOException {
        JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(manifestBytes), StandardCharsets.UTF_8));
        reader.setStrictness(Strictness.STRICT);

        Integer schema = null;
        String repository = null;
        ClientVersion version = null;
        String artifact = null;
        Long size = null;
        String sha256 = null;
        Integer minJava = null;
        Integer installerProtocol = null;
        BundleInfo bundle = null;

        Set<String> seenFields = new HashSet<>();

        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!seenFields.add(name)) {
                throw new IllegalArgumentException("Duplicate field in manifest: " + name);
            }
            switch (name) {
                case "schema" -> {
                    if (reader.peek() != JsonToken.NUMBER) {
                        throw new IllegalArgumentException("Field schema must be an integer");
                    }
                    schema = reader.nextInt();
                }
                case "repository" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("Field repository must be a string");
                    }
                    repository = reader.nextString();
                }
                case "version" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("Field version must be a string");
                    }
                    String rawVer = reader.nextString();
                    if (rawVer.startsWith("v") || rawVer.startsWith("V")) {
                        throw new IllegalArgumentException("Manifest version must not start with 'v': " + rawVer);
                    }
                    version = ClientVersion.parse(rawVer);
                }
                case "artifact" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("Field artifact must be a string");
                    }
                    artifact = reader.nextString();
                }
                case "size" -> {
                    if (reader.peek() != JsonToken.NUMBER) {
                        throw new IllegalArgumentException("Field size must be a number");
                    }
                    size = reader.nextLong();
                }
                case "sha256" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("Field sha256 must be a string");
                    }
                    sha256 = reader.nextString();
                }
                case "minJava" -> {
                    if (reader.peek() != JsonToken.NUMBER) {
                        throw new IllegalArgumentException("Field minJava must be an integer");
                    }
                    minJava = reader.nextInt();
                }
                case "installerProtocol" -> {
                    if (reader.peek() != JsonToken.NUMBER) {
                        throw new IllegalArgumentException("Field installerProtocol must be an integer");
                    }
                    installerProtocol = reader.nextInt();
                }
                case "bundle" -> {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        throw new IllegalArgumentException("Field bundle must be an object");
                    }
                    bundle = parseBundle(reader);
                }
                default -> throw new IllegalArgumentException("Unknown field in manifest: " + name);
            }
        }
        reader.endObject();

        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IllegalArgumentException("Trailing data after manifest JSON");
        }

        if (schema == null || repository == null || version == null || artifact == null
                || size == null || sha256 == null || minJava == null || installerProtocol == null || bundle == null) {
            throw new IllegalArgumentException("Manifest missing required fields");
        }

        if (!expectedRepository.equals(repository)) {
            throw new IllegalArgumentException("Manifest repository mismatch: expected " + expectedRepository + " but got " + repository);
        }

        return new ReleaseManifest(schema, repository, version, artifact, size, sha256, minJava, installerProtocol, bundle);
    }

    private static BundleInfo parseBundle(JsonReader reader) throws IOException {
        reader.beginObject();
        Set<String> seen = new HashSet<>();
        String name = null;
        Long size = null;
        String sha256 = null;

        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!seen.add(field)) {
                throw new IllegalArgumentException("Duplicate field in bundle: " + field);
            }
            switch (field) {
                case "name" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("bundle.name must be a string");
                    }
                    name = reader.nextString();
                }
                case "size" -> {
                    if (reader.peek() != JsonToken.NUMBER) {
                        throw new IllegalArgumentException("bundle.size must be a number");
                    }
                    size = reader.nextLong();
                }
                case "sha256" -> {
                    if (reader.peek() != JsonToken.STRING) {
                        throw new IllegalArgumentException("bundle.sha256 must be a string");
                    }
                    sha256 = reader.nextString();
                }
                default -> throw new IllegalArgumentException("Unknown field in bundle: " + field);
            }
        }
        reader.endObject();

        if (name == null || size == null || sha256 == null) {
            throw new IllegalArgumentException("Missing required bundle fields");
        }

        return new BundleInfo(name, size, sha256);
    }

    public String toJson() {
        return "{\n"
                + "  \"schema\": " + schema + ",\n"
                + "  \"repository\": \"" + repository + "\",\n"
                + "  \"version\": \"" + version + "\",\n"
                + "  \"artifact\": \"" + artifact + "\",\n"
                + "  \"size\": " + size + ",\n"
                + "  \"sha256\": \"" + sha256 + "\",\n"
                + "  \"minJava\": " + minJava + ",\n"
                + "  \"installerProtocol\": " + installerProtocol + ",\n"
                + "  \"bundle\": {\n"
                + "    \"name\": \"" + bundle.name() + "\",\n"
                + "    \"size\": " + bundle.size() + ",\n"
                + "    \"sha256\": \"" + bundle.sha256() + "\"\n"
                + "  }\n"
                + "}";
    }

    public byte[] toJsonUtf8() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }
}
