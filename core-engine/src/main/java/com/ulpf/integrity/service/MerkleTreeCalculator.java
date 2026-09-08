package com.ulpf.integrity.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary Merkle Tree calculator for log batch tamper-evidence.
 * Computes pair-wise SHA-256 hashes of leaf node raw log payload hashes up to the Merkle Root.
 */
@Component
public class MerkleTreeCalculator {

    private static final String EMPTY_ROOT_HASH = hashSha256("");

    /**
     * Compute Merkle Root from a list of SHA-256 leaf node hashes.
     * 
     * @param leafHashes List of hex-encoded SHA-256 hashes of individual log payloads
     * @return Hex-encoded 64-character SHA-256 Merkle Root string
     */
    public String calculateMerkleRoot(List<String> leafHashes) {
        if (leafHashes == null || leafHashes.isEmpty()) {
            return EMPTY_ROOT_HASH;
        }

        List<String> currentLevel = new ArrayList<>(leafHashes);

        while (currentLevel.size() > 1) {
            // Duplicate last node if current level has odd size
            if (currentLevel.size() % 2 != 0) {
                currentLevel.add(currentLevel.get(currentLevel.size() - 1));
            }

            List<String> nextLevel = new ArrayList<>();
            for (int i = 0; i < currentLevel.size(); i += 2) {
                String left = currentLevel.get(i);
                String right = currentLevel.get(i + 1);
                String parentHash = hashSha256(left + right);
                nextLevel.add(parentHash);
            }
            currentLevel = nextLevel;
        }

        return currentLevel.get(0);
    }

    /**
     * Hash individual raw log payload string to SHA-256 hex string.
     */
    public static String hashSha256(String raw) {
        if (raw == null) {
            raw = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 digest algorithm missing", e);
        }
    }
}
