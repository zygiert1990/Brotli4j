/*
 *   Copyright (c) 2020-2025, Aayush Atharva
 *
 *   Brotli4j licenses this file to you under the
 *   Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.aayushatharva.brotli4j.encoder;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.common.BrotliCommon;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncoderTest {

    private static final byte[] compressedData = new byte[]{-117, 1, -128, 77, 101, 111, 119, 3};
    private static final int quality = 5;
    private static final int lgwin = 16;
    // "Some long text, very long text" compressed with quality and lgwin above.
    private static final byte[] compressedWithQualityAndWindow = new byte[]{
            -94, 3, 0, -128, -128, 110, 44, -43, -47, 109, -51, -112, 31, 69, -112,
            35, 90, -86, 0, 85, -125, 37, -69, 68, 28, -84, 115, -80, -102, 7};
    private static final byte[] dictionary = "Some dictionary content shared by encoder and decoder".getBytes();

    @BeforeAll
    static void load() {
        Brotli4jLoader.ensureAvailability();
    }

    @Test
    void compress() throws IOException {
        assertArrayEquals(compressedData, Encoder.compress("Meow".getBytes()));
    }

    @Test
    void compressWithQuality() throws IOException {
        assertArrayEquals(compressedData, Encoder.compress("Meow".getBytes(), Encoder.Parameters.create(6)));
    }

    @Test
    void compressWithQualityAndByteBuffer() throws IOException {
        ByteBuf src = Unpooled.wrappedBuffer("Meow".getBytes());
        ByteBuf dst = Unpooled.directBuffer();
        Encoders.compress(src, dst);

        assertArrayEquals(compressedData, ByteBufUtil.getBytes(dst));
    }

    @Test
    void compressWithModes() throws IOException {
        final byte[] text = "Some long text, very long text".getBytes();
        final Encoder.Parameters parameters = new Encoder.Parameters();

        final byte[] compressedGeneric = Encoder.compress(text, parameters.setMode(Encoder.Mode.GENERIC));
        assertEquals(34, compressedGeneric.length);

        final byte[] compressedText = Encoder.compress(text, parameters.setMode(Encoder.Mode.TEXT));
        assertEquals(34, compressedText.length);

        final byte[] compressedFont = Encoder.compress(text, parameters.setMode(Encoder.Mode.FONT));
        assertEquals(31, compressedFont.length);
    }

    @Test
    void encodeModeEnumValues() {
        assertEquals(Encoder.Mode.FONT, Encoder.Mode.of(Encoder.Mode.FONT.ordinal()));
        assertEquals(Encoder.Mode.TEXT, Encoder.Mode.of(Encoder.Mode.TEXT.ordinal()));
        assertEquals(Encoder.Mode.GENERIC, Encoder.Mode.of(Encoder.Mode.GENERIC.ordinal()));
    }


    @Test
    void ensureDictionaryDataRemainsAfterGC() throws IOException, InterruptedException {
        // We hard code the compressed data, since the dictionary could also be collected just before our first compression
        final byte[] expectedCompression = new byte[]{27, 43, 0, -8, 37, 0, -62, -104, -40, -63, 0};
        final String dictionaryData = "This is some data to be used as a dictionary";
        final byte[] rawBytes = dictionaryData.getBytes(); // Use dictionary also as data to keep it small
        final PreparedDictionary dic = Encoder.prepareDictionary(BrotliCommon.makeNative(dictionaryData.getBytes()), 0);

        // Create gc pressure to trigger potential collection of dictionary data
        ArrayList<Integer> hashes = new ArrayList<>();
        for (int i = 0; i < 1_000_000; i++) {
            String obj = String.valueOf(Math.random());
            hashes.add(obj.hashCode());
        }
        hashes = null;
        System.gc();

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             BrotliOutputStream brotliOutputStream = new BrotliOutputStream(byteArrayOutputStream)) {
            brotliOutputStream.attachDictionary(dic);
            brotliOutputStream.write(rawBytes);
            brotliOutputStream.close();
            byteArrayOutputStream.close();
            assertArrayEquals(expectedCompression, byteArrayOutputStream.toByteArray());  // Otherwise the GC already cleared the data
        }
    }

    @Test
    void compressWithQualityAndWindow() throws IOException {
        // A lost or swapped quality / lgwin parameter still round-trips but changes the bytes.
        assertArrayEquals(compressedWithQualityAndWindow,
                Encoder.compress("Some long text, very long text".getBytes(), Encoder.Parameters.create(quality, lgwin)));
    }

    @Test
    void prepareDictionaryErrors() {
        // Capacity must be in (0, 2^30).
        assertThrows(IllegalStateException.class,
                () -> Encoder.prepareDictionary(ByteBuffer.allocateDirect(0), 0));
        // Serialized dictionaries need BROTLI_EXPERIMENTAL, which the native library is built without.
        assertThrows(IllegalStateException.class,
                () -> Encoder.prepareDictionary(BrotliCommon.makeNative(dictionary), 1));
    }

    @Test
    void closePreparedDictionaryTwice() throws Exception {
        // Native prepared dictionaries implement AutoCloseable; close() frees native memory.
        AutoCloseable prepared = (AutoCloseable) Encoder.prepareDictionary(BrotliCommon.makeNative(dictionary), 0);
        prepared.close();
        // The second close passes null to native code, which ignores it.
        assertDoesNotThrow(prepared::close);
    }

    @Test
    void failedAttachStillUsesDictionarySlot() throws Exception {
        PreparedDictionary prepared = Encoder.prepareDictionary(BrotliCommon.makeNative(dictionary), 0);
        EncoderJNI.Wrapper encoder = new EncoderJNI.Wrapper(1024, -1, -1, null);
        try {
            // Unknown magic is rejected by Brotli, but the binding still counts the slot.
            for (int i = 0; i < 15; i++) {
                assertFalse(encoder.attachDictionary(ByteBuffer.allocateDirect(16)));
            }
            assertFalse(encoder.attachDictionary(prepared.getData()));
        } finally {
            encoder.destroy();
            // Native prepared dictionaries implement AutoCloseable; free only after the encoder is destroyed.
            ((AutoCloseable) prepared).close();
        }
    }
}
