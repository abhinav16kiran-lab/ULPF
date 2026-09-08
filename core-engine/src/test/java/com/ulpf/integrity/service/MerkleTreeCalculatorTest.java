package com.ulpf.integrity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MerkleTreeCalculatorTest {

    private MerkleTreeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new MerkleTreeCalculator();
    }

    @Test
    void testCalculateMerkleRootForSingleLeaf() {
        String leaf1 = MerkleTreeCalculator.hashSha256("payload_1");
        String root = calculator.calculateMerkleRoot(List.of(leaf1));

        assertEquals(leaf1, root);
    }

    @Test
    void testCalculateMerkleRootForEvenNumberOfLeaves() {
        String leaf1 = MerkleTreeCalculator.hashSha256("payload_1");
        String leaf2 = MerkleTreeCalculator.hashSha256("payload_2");

        String root = calculator.calculateMerkleRoot(List.of(leaf1, leaf2));

        assertNotNull(root);
        assertEquals(64, root.length());
        String expected = MerkleTreeCalculator.hashSha256(leaf1 + leaf2);
        assertEquals(expected, root);
    }

    @Test
    void testCalculateMerkleRootForOddNumberOfLeaves() {
        String leaf1 = MerkleTreeCalculator.hashSha256("payload_1");
        String leaf2 = MerkleTreeCalculator.hashSha256("payload_2");
        String leaf3 = MerkleTreeCalculator.hashSha256("payload_3");

        String root = calculator.calculateMerkleRoot(List.of(leaf1, leaf2, leaf3));

        assertNotNull(root);
        assertEquals(64, root.length());

        // Level 1: pair(leaf1, leaf2) -> h12, pair(leaf3, leaf3 [duplicated]) -> h33
        String h12 = MerkleTreeCalculator.hashSha256(leaf1 + leaf2);
        String h33 = MerkleTreeCalculator.hashSha256(leaf3 + leaf3);
        String expectedRoot = MerkleTreeCalculator.hashSha256(h12 + h33);

        assertEquals(expectedRoot, root);
    }

    @Test
    void testTamperedPayloadChangesMerkleRootCompletely() {
        String leaf1 = MerkleTreeCalculator.hashSha256("original_payload_1");
        String leaf2 = MerkleTreeCalculator.hashSha256("original_payload_2");
        String originalRoot = calculator.calculateMerkleRoot(List.of(leaf1, leaf2));

        String tamperedLeaf1 = MerkleTreeCalculator.hashSha256("tampered_payload_1");
        String tamperedRoot = calculator.calculateMerkleRoot(List.of(tamperedLeaf1, leaf2));

        assertNotEquals(originalRoot, tamperedRoot);
    }
}
