package tools;

import vn.edu.p2p.peer.update.ClientVersion;
import vn.edu.p2p.peer.update.ReleaseManifest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ReleaseTool {

    public static final String EXPECTED_REPOSITORY = "Megumi2910/p2p-file-sharing";

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsageAndExit();
        }

        String mode = args[0];
        try {
            switch (mode) {
                case "keygen" -> {
                    if (args.length < 2) {
                        System.err.println("Usage: ReleaseTool keygen <directory>");
                        System.exit(1);
                    }
                    runKeygen(Path.of(args[1]));
                }
                case "validate" -> {
                    if (args.length < 3) {
                        System.err.println("Usage: ReleaseTool validate <jarPath> <zipPath>");
                        System.exit(1);
                    }
                    runValidate(Path.of(args[1]), Path.of(args[2]));
                }
                case "sign" -> {
                    if (args.length < 4) {
                        System.err.println("Usage: ReleaseTool sign <jarPath> <zipPath> <outputDir>");
                        System.exit(1);
                    }
                    runSign(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
                }
                case "verify" -> {
                    if (args.length < 6) {
                        System.err.println("Usage: ReleaseTool verify <manifestPath> <signaturePath> <jarPath> <zipPath> <publicKeyPath>");
                        System.exit(1);
                    }
                    runVerify(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
                }
                default -> {
                    System.err.println("Unknown command: " + mode);
                    printUsageAndExit();
                }
            }
        } catch (Exception ex) {
            System.err.println("ReleaseTool error: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void printUsageAndExit() {
        System.err.println("ReleaseTool CLI modes:");
        System.err.println("  keygen <directory>");
        System.err.println("  validate <jarPath> <zipPath>");
        System.err.println("  sign <jarPath> <zipPath> <outputDir>");
        System.err.println("  verify <manifestPath> <signaturePath> <jarPath> <zipPath> <publicKeyPath>");
        System.exit(1);
    }

    public static void runKeygen(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path privPath = dir.resolve("private.key");
        Path pubPath = dir.resolve("public.key");

        if (Files.exists(privPath)) {
            throw new IOException("Private key file already exists: " + privPath);
        }
        if (Files.exists(pubPath)) {
            throw new IOException("Public key file already exists: " + pubPath);
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();

        String privBase64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pubBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        Files.writeString(privPath, privBase64);
        Files.writeString(pubPath, pubBase64);

        byte[] pubSha = MessageDigest.getInstance("SHA-256").digest(kp.getPublic().getEncoded());
        String fingerprint = HexFormat.of().formatHex(pubSha);

        System.out.println("Keypair generated successfully.");
        System.out.println("Public key path: " + pubPath.toAbsolutePath());
        System.out.println("Public key fingerprint (SHA-256): " + fingerprint);
        System.out.println("Public key base64: " + pubBase64);
        System.out.println("Private key stored securely at: " + privPath.toAbsolutePath());
    }

    public static ValidationResult runValidate(Path jarPath, Path zipPath) throws Exception {
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException("JAR file not found: " + jarPath);
        }
        if (!Files.isRegularFile(zipPath)) {
            throw new IOException("ZIP bundle not found: " + zipPath);
        }

        // Validate JAR
        ClientVersion version;
        PublicKey publicKey;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest mf = jar.getManifest();
            if (mf == null) {
                throw new SecurityException("JAR missing META-INF/MANIFEST.MF");
            }
            String mainClass = mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            if (!"vn.edu.p2p.peer.PeerApplication".equals(mainClass)) {
                throw new SecurityException("Main-Class mismatch: " + mainClass);
            }

            JarEntry bpEntry = jar.getJarEntry("vn/edu/p2p/peer/update/build.properties");
            if (bpEntry == null) {
                throw new SecurityException("JAR missing vn/edu/p2p/peer/update/build.properties");
            }
            Properties props = new Properties();
            try (InputStream in = jar.getInputStream(bpEntry)) {
                props.load(in);
            }

            String verStr = props.getProperty("version", props.getProperty("client.version", "")).trim();
            if (verStr.isBlank() || verStr.startsWith("${") || verStr.contains("-dev")) {
                throw new SecurityException("JAR contains development or unreplaced version: " + verStr);
            }
            version = ClientVersion.parse(verStr);

            String repoStr = props.getProperty("repository", props.getProperty("client.repository", "")).trim();
            if (!EXPECTED_REPOSITORY.equals(repoStr)) {
                throw new SecurityException("JAR repository mismatch: " + repoStr);
            }

            String pubKeyStr = props.getProperty("publicKey", props.getProperty("client.publicKey", "")).trim();
            if (pubKeyStr.isBlank() || pubKeyStr.startsWith("${")) {
                throw new SecurityException("JAR missing embedded release public key");
            }
            byte[] pubBytes = Base64.getDecoder().decode(pubKeyStr);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            publicKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

            String protoStr = props.getProperty("installerProtocol", props.getProperty("client.installerProtocol", "1")).trim();
            if (!"1".equals(protoStr)) {
                throw new SecurityException("JAR installer protocol mismatch: " + protoStr);
            }
        }

        // Validate ZIP
        Set<String> expectedEntries = Set.of(
                "p2p-client/",
                "p2p-client/peer-app.jar",
                "p2p-client/run-peer-linux.sh",
                "p2p-client/run-peer-windows.bat",
                "p2p-client/peer.properties.example"
        );

        String standaloneJarSha = sha256(jarPath);
        String zipEmbeddedJarSha = null;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            Set<String> foundEntries = new HashSet<>();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                String name = entry.getName().replace('\\', '/');
                foundEntries.add(name);
                if ("p2p-client/peer-app.jar".equals(name)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            md.update(buf, 0, r);
                        }
                        zipEmbeddedJarSha = HexFormat.of().formatHex(md.digest());
                    }
                }
            }

            if (!foundEntries.equals(expectedEntries)) {
                throw new SecurityException("ZIP archive entries mismatch. Expected exactly " + expectedEntries + " but found " + foundEntries);
            }
        }

        if (!standaloneJarSha.equalsIgnoreCase(zipEmbeddedJarSha)) {
            throw new SecurityException("Standalone JAR and ZIP embedded JAR are not byte-identical");
        }

        System.out.println("Validation PASSED for version " + version);
        return new ValidationResult(version, publicKey, standaloneJarSha);
    }

    public static void runSign(Path jarPath, Path zipPath, Path outputDir) throws Exception {
        ValidationResult val = runValidate(jarPath, zipPath);
        Files.createDirectories(outputDir);

        String privEnv = System.getenv("CLIENT_RELEASE_PRIVATE_KEY");
        if (privEnv == null || privEnv.isBlank()) {
            throw new IllegalStateException("CLIENT_RELEASE_PRIVATE_KEY environment variable is not set");
        }

        byte[] privBytes = Base64.getDecoder().decode(privEnv.trim());
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));

        // Verify private key corresponds to embedded public key
        byte[] testChallenge = "p2p-release-challenge".getBytes(StandardCharsets.UTF_8);
        Signature testSig = Signature.getInstance("Ed25519");
        testSig.initSign(privateKey);
        testSig.update(testChallenge);
        byte[] testSigBytes = testSig.sign();

        Signature verifyTest = Signature.getInstance("Ed25519");
        verifyTest.initVerify(val.publicKey());
        verifyTest.update(testChallenge);
        if (!verifyTest.verify(testSigBytes)) {
            throw new SecurityException("Private key does not correspond to the public key embedded in the JAR");
        }

        long jarSize = Files.size(jarPath);
        String jarSha = val.jarSha();
        long zipSize = Files.size(zipPath);
        String zipSha = sha256(zipPath);

        ReleaseManifest manifest = new ReleaseManifest(
                1,
                EXPECTED_REPOSITORY,
                val.version(),
                "peer-app.jar",
                jarSize,
                jarSha,
                21,
                1,
                new ReleaseManifest.BundleInfo("p2p-client-" + val.version() + ".zip", zipSize, zipSha)
        );

        byte[] manifestBytes = manifest.toJsonUtf8();
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(manifestBytes);
        byte[] signatureBytes = sig.sign();

        // Verify generated signature
        ReleaseManifest.parseAndVerify(manifestBytes, signatureBytes, val.publicKey(), EXPECTED_REPOSITORY);

        Path manifestOut = outputDir.resolve("update-manifest.json");
        Path sigOut = outputDir.resolve("update-manifest.sig");
        Path sumsOut = outputDir.resolve("SHA256SUMS");

        Files.write(manifestOut, manifestBytes);
        Files.write(sigOut, signatureBytes);

        String manifestSha = sha256(manifestOut);
        String sigSha = sha256(sigOut);

        String sumsContent = jarSha + "  peer-app.jar\n"
                + zipSha + "  p2p-client-" + val.version() + ".zip\n"
                + manifestSha + "  update-manifest.json\n"
                + sigSha + "  update-manifest.sig\n";
        Files.writeString(sumsOut, sumsContent);

        System.out.println("Signing completed successfully.");
        System.out.println("Output files created in: " + outputDir.toAbsolutePath());
        System.out.println("  - update-manifest.json");
        System.out.println("  - update-manifest.sig");
        System.out.println("  - SHA256SUMS");
    }

    public static void runVerify(Path manifestPath, Path sigPath, Path jarPath, Path zipPath, Path pubKeyPath) throws Exception {
        byte[] pubBytes = Base64.getDecoder().decode(Files.readString(pubKeyPath).trim());
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        byte[] sigBytes = Files.readAllBytes(sigPath);

        ReleaseManifest manifest = ReleaseManifest.parseAndVerify(manifestBytes, sigBytes, pubKey, EXPECTED_REPOSITORY);

        long jarSize = Files.size(jarPath);
        String jarSha = sha256(jarPath);
        if (manifest.size() != jarSize || !manifest.sha256().equalsIgnoreCase(jarSha)) {
            throw new SecurityException("JAR size or SHA-256 mismatch against manifest");
        }

        long zipSize = Files.size(zipPath);
        String zipSha = sha256(zipPath);
        if (manifest.bundle().size() != zipSize || !manifest.bundle().sha256().equalsIgnoreCase(zipSha)) {
            throw new SecurityException("ZIP size or SHA-256 mismatch against manifest");
        }

        System.out.println("Verification PASSED for version " + manifest.version());
    }

    public record ValidationResult(ClientVersion version, PublicKey publicKey, String jarSha) {}

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception ex) {
            throw new IOException("Failed to hash file: " + file, ex);
        }
    }
}
