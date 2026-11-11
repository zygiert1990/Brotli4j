/* Copyright 2017 Google Inc. All Rights Reserved.

   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT
*/
package com.aayushatharva.brotli4j.common;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * FFM implementation (JDK 22+), a line-by-line port of {@code common_jni.cc}.
 */
final class FfmNativeFunctionCaller implements NativeFunctionCaller {

    private static final String SET_DICTIONARY_DATA = "BrotliSetDictionaryData";
    private static final String GET_DICTIONARY = "BrotliGetDictionary";

    // Benign race: at worst two threads both look the symbols up.
    private static boolean linked;

    /**
     * Keeps the buffer reachable once brotli uses it as dictionary data
     * (the JNI version keeps a global reference: an intended memory leak).
     */
    @SuppressWarnings("unused")
    private static ByteBuffer dictionaryData;

    @Override
    public boolean nativeSetDictionaryData(ByteBuffer buffer) {
        ensureLinked();
        // NewGlobalRef(null) and GetDirectBufferAddress(heap buffer) both yield NULL.
        if (buffer == null || !buffer.isDirect()) {
            return false;
        }
        long data = MemorySegment.ofBuffer(buffer.duplicate().clear()).address();
        if (data == 0) {
            return false;
        }
        try {
            Bindings.SET_DICTIONARY_DATA.invokeExact(MemorySegment.ofAddress(data));
            MemorySegment dictionary = (MemorySegment) Bindings.GET_DICTIONARY.invokeExact();
            if (dictionary.get(ADDRESS, Bindings.DICTIONARY_DATA_OFFSET).address() == data) {
                dictionaryData = buffer;
            }
        } catch (Throwable t) {
            throw new AssertionError(t);
        } finally {
            Reference.reachabilityFence(buffer);
        }
        return true;
    }

    /**
     * Fails like an unlinked JNI native (retried on the next call) while the library isn't loaded yet.
     */
    private static void ensureLinked() {
        if (linked) {
            return;
        }
        SymbolLookup lookup = SymbolLookup.loaderLookup();
        if (lookup.find(SET_DICTIONARY_DATA).isEmpty() || lookup.find(GET_DICTIONARY).isEmpty()) {
            throw new UnsatisfiedLinkError(
                    "'boolean com.aayushatharva.brotli4j.common.CommonJNI.nativeSetDictionaryData(java.nio.ByteBuffer)'");
        }
        linked = true;
    }

    /**
     * Downcall handles and layouts, initialized on first use after {@link #ensureLinked()} succeeded.
     */
    private static final class Bindings {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

        /** {@code BrotliDictionary} from {@code common/dictionary.h}; only {@code data} is read. */
        static final MemoryLayout DICTIONARY = MemoryLayout.structLayout(
                MemoryLayout.sequenceLayout(32, JAVA_BYTE).withName("size_bits_by_length"),
                MemoryLayout.sequenceLayout(32, JAVA_INT).withName("offsets_by_length"),
                LINKER.canonicalLayouts().get("size_t").withName("data_size"),
                ADDRESS.withName("data"));
        static final long DICTIONARY_DATA_OFFSET =
                DICTIONARY.byteOffset(MemoryLayout.PathElement.groupElement("data"));

        static final MethodHandle SET_DICTIONARY_DATA = downcall(FfmNativeFunctionCaller.SET_DICTIONARY_DATA,
                FunctionDescriptor.ofVoid(ADDRESS));
        // The target layout sizes the returned segment, so it can be read without reinterpret().
        static final MethodHandle GET_DICTIONARY = downcall(FfmNativeFunctionCaller.GET_DICTIONARY,
                FunctionDescriptor.of(ADDRESS.withTargetLayout(DICTIONARY)));

        private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(LOOKUP.find(name).orElseThrow(), descriptor);
        }
    }
}
