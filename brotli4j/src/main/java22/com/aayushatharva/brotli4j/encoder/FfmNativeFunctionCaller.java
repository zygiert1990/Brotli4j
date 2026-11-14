/* Copyright 2017 Google Inc. All Rights Reserved.

   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT
*/
package com.aayushatharva.brotli4j.encoder;

import com.aayushatharva.brotli4j.internal.NativeBackend;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * FFM implementation (JDK 22+), a line-by-line port of {@code encoder_jni.cc}.
 * One instance per {@link EncoderJNI.Wrapper}: its fields are the C {@code EncoderHandle}.
 * The dictionary methods don't use them.
 */
final class FfmNativeFunctionCaller implements NativeFunctionCaller {

    private static final String CREATE_INSTANCE = "BrotliEncoderCreateInstance";
    private static final String DESTROY_INSTANCE = "BrotliEncoderDestroyInstance";
    private static final String SET_PARAMETER = "BrotliEncoderSetParameter";
    private static final String COMPRESS_STREAM = "BrotliEncoderCompressStream";
    private static final String HAS_MORE_OUTPUT = "BrotliEncoderHasMoreOutput";
    private static final String TAKE_OUTPUT = "BrotliEncoderTakeOutput";
    private static final String IS_FINISHED = "BrotliEncoderIsFinished";
    private static final String ATTACH_PREPARED_DICTIONARY = "BrotliEncoderAttachPreparedDictionary";
    private static final String PREPARE_DICTIONARY = "BrotliEncoderPrepareDictionary";
    private static final String DESTROY_PREPARED_DICTIONARY = "BrotliEncoderDestroyPreparedDictionary";

    // BrotliEncoderParameter (brotli/encode.h)
    private static final int PARAM_MODE = 0;
    private static final int PARAM_QUALITY = 1;
    private static final int PARAM_LGWIN = 2;
    // BrotliEncoderOperation (brotli/encode.h)
    private static final int OPERATION_PROCESS = 0;
    private static final int OPERATION_FLUSH = 1;
    private static final int OPERATION_FINISH = 2;
    // brotli/encode.h
    private static final int MAX_QUALITY = 11;

    private static final int MAX_DICTIONARIES = 15;

    private static final String[] SYMBOLS = {CREATE_INSTANCE, DESTROY_INSTANCE, SET_PARAMETER, COMPRESS_STREAM,
            HAS_MORE_OUTPUT, TAKE_OUTPUT, IS_FINISHED, ATTACH_PREPARED_DICTIONARY, PREPARE_DICTIONARY,
            DESTROY_PREPARED_DICTIONARY};
    // Benign race: at worst two threads both look the symbols up.
    private static boolean linked;

    // EncoderHandle. Shared, not confined: a Wrapper may be handed between threads.
    private Arena arena;
    private MemorySegment state;
    private MemorySegment inputStart;
    private long inputOffset;
    private long inputLast;
    /** JNI global references: keep attached dictionaries alive until {@link #nativeDestroy}. */
    private final List<ByteBuffer> dictionaryRefs = new ArrayList<>(MAX_DICTIONARIES);

    // Out-parameters, which C keeps on its stack: slices of one arena allocation.
    private MemorySegment inSize;
    private MemorySegment in;
    private MemorySegment outSize;
    private MemorySegment dataLength;

    @Override
    public ByteBuffer nativeCreate(long[] context) {
        // Here and in nativePrepareDictionary: the other methods run on a created encoder or a prepared dictionary.
        if (!linked) {
            NativeBackend.ensureLinked(
                    "'java.nio.ByteBuffer com.aayushatharva.brotli4j.encoder.EncoderJNI.nativeCreate(long[])'", SYMBOLS);
            linked = true;
        }
        boolean ok = true;
        long inputSize = Bindings.toSizeT(context[1]);
        context[0] = 0;
        arena = Arena.ofShared();
        // Java only (C uses stack variables). Allocated first: if this throws, the arena holds nothing to free.
        MemorySegment cells = arena.allocate(Bindings.CELLS);
        inSize = cells.asSlice(Bindings.IN_SIZE_OFFSET, Bindings.SIZE_T.byteSize());
        in = cells.asSlice(Bindings.IN_OFFSET, ADDRESS.byteSize());
        outSize = cells.asSlice(Bindings.OUT_SIZE_OFFSET, Bindings.SIZE_T.byteSize());
        dataLength = cells.asSlice(Bindings.DATA_LENGTH_OFFSET, Bindings.SIZE_T.byteSize());

        // Java only: NewDirectByteBuffer accepts sizes above MAX_BUFFER_SIZE, asByteBuffer() below doesn't.
        if (inputSize == 0 || Long.compareUnsigned(inputSize, NativeBackend.MAX_BUFFER_SIZE) > 0) {
            ok = false;
        } else {
            // new (std::nothrow) yields NULL where allocate throws: OutOfMemoryError when malloc fails.
            try {
                inputStart = arena.allocate(inputSize);
            } catch (OutOfMemoryError e) {
                ok = false;
            }
        }

        if (ok) {
            try {
                state = (MemorySegment) Bindings.CREATE_INSTANCE.invokeExact(
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
            ok = state.address() != 0;
        }

        if (ok) {
            int quality = (int) context[2];
            if (quality >= 0) {
                setParameter(PARAM_QUALITY, quality);
            }
            int lgwin = (int) context[3];
            if (lgwin >= 0) {
                setParameter(PARAM_LGWIN, lgwin);
            }
            int mode = (int) context[4];
            if (mode >= 0) {
                setParameter(PARAM_MODE, mode);
            }
        }

        if (ok) {
            context[0] = state.address();
        } else {
            arena.close();
        }

        if (!ok) {
            return null;
        }

        return inputStart.asByteBuffer();
    }

    @Override
    public void nativePush(long[] context, int length) {
        int operation = (int) context[1];
        context[1] = 0;  /* ERROR */

        int op;
        switch (operation) {
            case 0: op = OPERATION_PROCESS; break;
            case 1: op = OPERATION_FLUSH; break;
            case 2: op = OPERATION_FINISH; break;
            default: return;  /* ERROR */
        }

        if (length != 0) {
            /* Still have unconsumed data. Workflow is broken. */
            if (inputOffset < inputLast) {
                return;
            }
            inputOffset = 0;
            inputLast = Bindings.toSizeT(length);
        }

        /* Actual compression. */
        in.set(ADDRESS, 0, inputStart.asSlice(inputOffset));
        Bindings.setSize(inSize, inputLast - inputOffset);
        Bindings.setSize(outSize, 0);
        int status;
        try {
            status = (int) Bindings.COMPRESS_STREAM.invokeExact(
                    state, op, inSize, in, outSize, MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        inputOffset = inputLast - Bindings.getSize(inSize);
        if (status != 0) {
            context[1] = 1;
            context[2] = hasMoreOutput() ? 1 : 0;
            context[3] = (inputOffset != inputLast) ? 1 : 0;
            context[4] = isFinished() ? 1 : 0;
        }
    }

    @Override
    public ByteBuffer nativePull(long[] context) {
        Bindings.setSize(dataLength, 0);
        MemorySegment data;
        try {
            data = (MemorySegment) Bindings.TAKE_OUTPUT.invokeExact(state, dataLength);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        context[1] = 1;
        context[2] = hasMoreOutput() ? 1 : 0;
        context[3] = (inputOffset != inputLast) ? 1 : 0;
        context[4] = isFinished() ? 1 : 0;
        // NewDirectByteBuffer: big-endian view of brotli's output, valid until the next call.
        // Tied to the arena (brotli owns the memory, so no cleanup action): access after destroy throws.
        return data.reinterpret(Bindings.getSize(dataLength), arena, null).asByteBuffer();
    }

    @Override
    public void nativeDestroy(long[] context) {
        // Closed first, unlike C: it throws while another thread's downcall uses the arena, before anything is
        // freed. The input isn't referenced by brotli between calls; state is brotli's own (global scope).
        arena.close();
        try {
            Bindings.DESTROY_INSTANCE.invokeExact(state);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        dictionaryRefs.clear();
    }

    @Override
    public boolean nativeAttachDictionary(long[] context, ByteBuffer dictionary) {
        MemorySegment address = null;

        boolean ok = true;
        if (dictionary == null) {
            ok = false;
        }
        if (ok && dictionaryRefs.size() >= MAX_DICTIONARIES) {
            ok = false;
        }
        // NewGlobalRef of a non-null object only fails when the JVM is out of memory.
        if (ok) {
            dictionaryRefs.add(dictionary);
            address = directBufferAddress(dictionary);
            ok = address != null;
        }
        if (ok) {
            try {
                ok = (int) Bindings.ATTACH_PREPARED_DICTIONARY.invokeExact(state, address) != 0;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        }

        return ok;
    }

    @Override
    public void nativeDestroyDictionary(ByteBuffer dictionary) {
        if (dictionary == null) {
            return;
        }
        MemorySegment address = directBufferAddress(dictionary);
        if (address == null) {
            return;
        }
        try {
            Bindings.DESTROY_PREPARED_DICTIONARY.invokeExact(address);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    @Override
    public ByteBuffer nativePrepareDictionary(ByteBuffer dictionary, long type) {
        if (!linked) {
            NativeBackend.ensureLinked(
                    "'java.nio.ByteBuffer com.aayushatharva.brotli4j.encoder.EncoderJNI.nativePrepareDictionary(java.nio.ByteBuffer, long)'",
                    SYMBOLS);
            linked = true;
        }
        if (dictionary == null) {
            return null;
        }
        MemorySegment address = directBufferAddress(dictionary);
        if (address == null) {
            return null;
        }
        long capacity = dictionary.capacity();
        if ((capacity <= 0) || (capacity >= (1 << 30))) {
            return null;
        }
        int dictionaryType = (int) type;
        long size = Bindings.toSizeT(capacity);
        MemorySegment preparedDictionary;
        try {
            preparedDictionary = (MemorySegment) Bindings.PREPARE_DICTIONARY.invokeExact(dictionaryType, size,
                    address, MAX_QUALITY, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        if (preparedDictionary.address() == 0) {
            return null;
        }
        /* Size is 4 - just enough to check magic bytes. */
        // NewDirectByteBuffer: brotli owns the memory until nativeDestroyDictionary (global scope, like JNI).
        return preparedDictionary.reinterpret(4).asByteBuffer();
    }

    /**
     * {@code GetDirectBufferAddress}: the buffer's base address regardless of position, or {@code null} for a heap
     * buffer. Passing the segment to a downcall keeps the buffer reachable during the call.
     */
    private static MemorySegment directBufferAddress(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            return null;
        }
        MemorySegment address = MemorySegment.ofBuffer(buffer.duplicate().clear());
        return address.address() != 0 ? address : null;
    }

    private void setParameter(int param, int value) {
        try {
            int ignored = (int) Bindings.SET_PARAMETER.invokeExact(state, param, value);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private boolean hasMoreOutput() {
        try {
            return (int) Bindings.HAS_MORE_OUTPUT.invokeExact(state) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private boolean isFinished() {
        try {
            return (int) Bindings.IS_FINISHED.invokeExact(state) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /**
     * Downcall handles and layouts, initialized on first use after {@link NativeBackend#ensureLinked} succeeded.
     */
    private static final class Bindings {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();
        private static final Linker.Option CRITICAL = Linker.Option.critical(false);

        /** {@code size_t}: 8 bytes on 64-bit, 4 on 32-bit. */
        static final ValueLayout SIZE_T = sizeT();

        /** The out-parameters; {@code size_t} and pointers have the same size, so there is no padding. */
        static final MemoryLayout CELLS = MemoryLayout.structLayout(
                SIZE_T.withName("in_size"),
                ADDRESS.withName("in"),
                SIZE_T.withName("out_size"),
                SIZE_T.withName("data_length"));
        static final long IN_SIZE_OFFSET = offset("in_size");
        static final long IN_OFFSET = offset("in");
        static final long OUT_SIZE_OFFSET = offset("out_size");
        static final long DATA_LENGTH_OFFSET = offset("data_length");

        /** {@code BrotliEncoderState* (brotli_alloc_func, brotli_free_func, void* opaque)} */
        static final MethodHandle CREATE_INSTANCE = downcall(FfmNativeFunctionCaller.CREATE_INSTANCE,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        /** {@code void (BrotliEncoderState*)} */
        static final MethodHandle DESTROY_INSTANCE = downcall(FfmNativeFunctionCaller.DESTROY_INSTANCE,
                FunctionDescriptor.ofVoid(ADDRESS));
        /** {@code BROTLI_BOOL (BrotliEncoderState*, BrotliEncoderParameter, uint32_t value)} */
        static final MethodHandle SET_PARAMETER = downcall(FfmNativeFunctionCaller.SET_PARAMETER,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), CRITICAL);
        /** {@code BROTLI_BOOL (state, BrotliEncoderOperation, size_t* available_in, const uint8_t** next_in,
         * size_t* available_out, uint8_t** next_out, size_t* total_out)}; long-running, so not critical. */
        static final MethodHandle COMPRESS_STREAM = downcall(FfmNativeFunctionCaller.COMPRESS_STREAM,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        /** {@code BROTLI_BOOL (BrotliEncoderState*)} */
        static final MethodHandle HAS_MORE_OUTPUT = downcall(FfmNativeFunctionCaller.HAS_MORE_OUTPUT,
                FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
        /** {@code const uint8_t* (BrotliEncoderState*, size_t* size)} */
        static final MethodHandle TAKE_OUTPUT = downcall(FfmNativeFunctionCaller.TAKE_OUTPUT,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), CRITICAL);
        /** {@code BROTLI_BOOL (const BrotliEncoderState*)} */
        static final MethodHandle IS_FINISHED = downcall(FfmNativeFunctionCaller.IS_FINISHED,
                FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
        /** {@code BROTLI_BOOL (BrotliEncoderState*, const BrotliEncoderPreparedDictionary*)} */
        static final MethodHandle ATTACH_PREPARED_DICTIONARY = downcall(
                FfmNativeFunctionCaller.ATTACH_PREPARED_DICTIONARY,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        /** {@code BrotliEncoderPreparedDictionary* (BrotliSharedDictionaryType, size_t data_size,
         * const uint8_t* data, int quality, brotli_alloc_func, brotli_free_func, void* opaque)};
         * {@code data_size} is retyped to {@code long} on 32-bit. Long-running, so not critical. */
        static final MethodHandle PREPARE_DICTIONARY = sizeTAsLong(downcall(FfmNativeFunctionCaller.PREPARE_DICTIONARY,
                FunctionDescriptor.of(ADDRESS, JAVA_INT, SIZE_T, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS)), 1);
        /** {@code void (BrotliEncoderPreparedDictionary*)} */
        static final MethodHandle DESTROY_PREPARED_DICTIONARY = downcall(
                FfmNativeFunctionCaller.DESTROY_PREPARED_DICTIONARY,
                FunctionDescriptor.ofVoid(ADDRESS));

        /** {@code (size_t) value}: keeps the low 32 bits on 32-bit platforms, like the C cast. */
        static long toSizeT(long value) {
            return SIZE_T.byteSize() == Integer.BYTES ? value & 0xFFFFFFFFL : value;
        }

        static long getSize(MemorySegment cell) {
            return SIZE_T instanceof ValueLayout.OfLong layout
                    ? cell.get(layout, 0)
                    : Integer.toUnsignedLong(cell.get((ValueLayout.OfInt) SIZE_T, 0));
        }

        static void setSize(MemorySegment cell, long value) {
            if (SIZE_T instanceof ValueLayout.OfLong layout) {
                cell.set(layout, 0, value);
            } else {
                cell.set((ValueLayout.OfInt) SIZE_T, 0, (int) value);
            }
        }

        /** The helpers below handle only a {@code long} or {@code int} {@code size_t}. */
        private static ValueLayout sizeT() {
            MemoryLayout sizeT = LINKER.canonicalLayouts().get("size_t");
            if (sizeT instanceof ValueLayout.OfLong || sizeT instanceof ValueLayout.OfInt) {
                return (ValueLayout) sizeT;
            }
            throw new UnsupportedOperationException("Unexpected size_t layout " + sizeT);
        }

        private static long offset(String name) {
            return CELLS.byteOffset(MemoryLayout.PathElement.groupElement(name));
        }

        private static MethodHandle downcall(String name, FunctionDescriptor descriptor, Linker.Option... options) {
            return LINKER.downcallHandle(LOOKUP.find(name).orElseThrow(), descriptor, options);
        }

        /** Lets callers pass a {@code long} for a {@code size_t} parameter; a no-op on 64-bit. */
        private static MethodHandle sizeTAsLong(MethodHandle handle, int parameter) {
            return MethodHandles.explicitCastArguments(handle, handle.type().changeParameterType(parameter, long.class));
        }
    }
}
