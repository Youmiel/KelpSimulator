package com.ishland.simulations.kelpsimulator.impl.opencl;

import com.ishland.simulations.kelpsimulator.impl.SimulationResult;
import com.ishland.simulations.kelpsimulator.impl.Simulator;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL11;
import org.lwjgl.opencl.CLEventCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.IntSummaryStatistics;
import java.util.LongSummaryStatistics;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.checkCLError;
import static org.lwjgl.opencl.CL10.CL_COMPLETE;
import static org.lwjgl.opencl.CL10.CL_MEM_WRITE_ONLY;
import static org.lwjgl.opencl.CL10.CL_SUCCESS;
import static org.lwjgl.opencl.CL10.clCreateBuffer;
import static org.lwjgl.opencl.CL10.clCreateKernel;
import static org.lwjgl.opencl.CL10.clEnqueueNDRangeKernel;
import static org.lwjgl.opencl.CL10.clFlush;
import static org.lwjgl.opencl.CL10.clReleaseEvent;
import static org.lwjgl.opencl.CL10.clReleaseKernel;
import static org.lwjgl.opencl.CL10.clReleaseMemObject;
import static org.lwjgl.opencl.CL10.clSetKernelArg1i;
import static org.lwjgl.opencl.CL10.clSetKernelArg1l;
import static org.lwjgl.opencl.CL10.clSetKernelArg1p;
import static org.lwjgl.opencl.CL10.clSetKernelArg1s;
import static org.lwjgl.opencl.CL10.nclEnqueueReadBuffer;
import static org.lwjgl.system.Checks.CHECKS;
import static org.lwjgl.system.Checks.checkSafe;
import static org.lwjgl.system.Checks.remainingSafe;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memAddressSafe;

public class OpenCLSimulationSession implements Simulator {

    private static final int SECTION_SIZE = 16 * 16 * 16;
    private static final int SECTION_SURFACE_SIZE = 16 * 16;
    private static final AtomicInteger GLOBAL_SERIAL = new AtomicInteger(0);

    private final int serial = GLOBAL_SERIAL.getAndIncrement();
    private final int randomTickSpeed;
    private final boolean schedulerFirst;
    private final int waterFlowDelay;
    private final int kelpCount;
    private final long testLength;
    private final int harvestPeriod;
    private final int heightLimit;
    private final ReductionMode reductionMode;

    private final Random random = new Random();

    private final CompletableFuture<SimulationResult> future = new CompletableFuture<>();

    public OpenCLSimulationSession(int randomTickSpeed, boolean schedulerFirst, int waterFlowDelay, int kelpCount, long testLength, int harvestPeriod, int heightLimit, ReductionMode reductionMode) {
        this.randomTickSpeed = randomTickSpeed;
        this.schedulerFirst = schedulerFirst;
        this.waterFlowDelay = waterFlowDelay;
        this.kelpCount = kelpCount;
        this.testLength = testLength;
        this.harvestPeriod = harvestPeriod;
        this.heightLimit = heightLimit;
        this.reductionMode = reductionMode;
    }

    public void submitTask(DeviceManager.OpenCLContext context, long program, long queue) {
        final long startTime = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errCodeRet = stack.callocInt(1);

            // prepare storage
//            log("Allocating memory resources");
            final int partSizeOriginal = (int) Math.ceil(kelpCount / 256.0);
            final int harvestSizeOriginal = (int) Math.floor((testLength + waterFlowDelay) / (double) (harvestPeriod + waterFlowDelay));
            final boolean isReducedKelp = reductionMode == ReductionMode.REDUCED_KELP_COUNT || reductionMode == ReductionMode.REDUCED_BOTH;
            final boolean isReducedLength = reductionMode == ReductionMode.REDUCED_TEST_LENGTH || reductionMode == ReductionMode.REDUCED_BOTH;
            final int partSize = isReducedKelp ? 1 : partSizeOriginal;
            final int harvestSize = isReducedLength ? 1 : harvestSizeOriginal;
            final long sectionedTotalStoragePointer = clCreateBuffer(context.getContext(), CL_MEM_WRITE_ONLY, ((long) partSize * harvestSize) << 3, errCodeRet);
            checkCLError(errCodeRet);

            // prepare kernel
//            log("Preparing kernel");
            final long kernel = clCreateKernel(program, "doWork", errCodeRet);
            checkCLError(errCodeRet);
            checkCLError(clSetKernelArg1i(kernel, 0, randomTickSpeed));
            checkCLError(clSetKernelArg1i(kernel, 1, schedulerFirst ? 1 : 0));
            checkCLError(clSetKernelArg1i(kernel, 2, waterFlowDelay));
            checkCLError(clSetKernelArg1i(kernel, 3, isReducedKelp ? 256 : kelpCount));
            checkCLError(clSetKernelArg1l(kernel, 4, isReducedLength ? harvestPeriod + waterFlowDelay : testLength));
            checkCLError(clSetKernelArg1i(kernel, 5, harvestPeriod));
            checkCLError(clSetKernelArg1i(kernel, 6, heightLimit));
            checkCLError(clSetKernelArg1l(kernel, 7, random.nextLong())); // seed
            checkCLError(clSetKernelArg1i(kernel, 8, harvestSize));
            checkCLError(clSetKernelArg1p(kernel, 9, sectionedTotalStoragePointer));

            // submit
//            log("Submitting to OpenCL");
            final PointerBuffer globalWorkSize = stack.callocPointer(2);
            globalWorkSize.put(partSize);
            globalWorkSize.put(harvestSize);
            globalWorkSize.position(0);
            final PointerBuffer eventPointer = stack.callocPointer(1);
            checkCLError(clEnqueueNDRangeKernel(queue, kernel, 2, null, globalWorkSize, null, null, eventPointer));
            final long eventptr = eventPointer.get(0);

            // waiting
//            log("Waiting for results");
            final CLEventCallback[] clEventCallback = new CLEventCallback[1];
            clEventCallback[0] = CLEventCallback.create((event, event_command_exec_status, user_data) -> {
                if (event_command_exec_status == CL_COMPLETE) {
                    DeviceManager.clThread.callBacks.add((unused, unused1, unused2) -> {
                        try {
//                            log("Reading results from OpenCL");
                            final LongBuffer sectionedTotalStorage = MemoryUtil.memAllocLong(partSize * harvestSize);
                            checkCLError(clFlush(queue));
                            checkCLError(oclEnqueueReadBuffer(queue, sectionedTotalStoragePointer, true, 0, sectionedTotalStorage, null, null));

                            // process result
//                            log("Processing results");
                            sectionedTotalStorage.position(0);
                            final long[] sectionedTotalArray = new long[kelpCount];
                            {
                                int sectionCounter = 0;
                                while (sectionedTotalStorage.hasRemaining()) {
                                    long sectionTotal = 0;
                                    for (int i = 0; i < harvestSize; i++) sectionTotal += sectionedTotalStorage.get();
                                    sectionedTotalArray[sectionCounter++] = sectionTotal;
                                }
                            }
                            final LongSummaryStatistics statistics = Arrays.stream(sectionedTotalArray).summaryStatistics();

                            sectionedTotalStorage.position(0);
                            final int[] rawStorageArray = new int[sectionedTotalStorage.remaining()];
                            {
                                int counter = 0;
                                while (sectionedTotalStorage.hasRemaining()) {
                                    rawStorageArray[counter ++] = (int) sectionedTotalStorage.get();
                                }
                            }
                            final IntSummaryStatistics statistics1 = Arrays.stream(rawStorageArray).summaryStatistics();

                            final double reductionRestoringMultiplier = getReductionRestoringMultiplier();

                            future.complete(new SimulationResult(
                                    new LongSummaryStatistics(kelpCount, (long) (statistics.getMin() / 256 * reductionRestoringMultiplier), (long) (statistics.getMax() / 256 * reductionRestoringMultiplier), (long) (statistics.getSum() * reductionRestoringMultiplier)),
                                    new IntSummaryStatistics((long) partSizeOriginal * harvestSizeOriginal, (int) (statistics1.getMin() / 256 * reductionRestoringMultiplier), (int) (statistics1.getMax() / 256 * reductionRestoringMultiplier), (long) (statistics1.getSum() * reductionRestoringMultiplier))
                            ));

//                            log("Cleaning up");
                            checkCLError(clReleaseEvent(eventptr));
                            clEventCallback[0].free();
                            checkCLError(clReleaseKernel(kernel));
                            checkCLError(clReleaseMemObject(sectionedTotalStoragePointer));
                            MemoryUtil.memFree(sectionedTotalStorage);
                        } catch (Throwable t) {
                            t.printStackTrace();
                            future.completeExceptionally(t);
                        }
                    });
                } else if (event_command_exec_status < 0) {
                    future.completeExceptionally(new RuntimeException("OpenCL Error " + event_command_exec_status + " while executing task"));
                } else {
                    System.out.println("Unknown status: " + event_command_exec_status);
                }
            });

            CL11.clSetEventCallback(eventptr, CL_SUCCESS, clEventCallback[0], NULL);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        } finally {
            future.thenRun(() -> log(String.format("Done. %.1f hours (%d gt), time elapsed: %.1fms", testLength / 20.0 / 60.0 / 60.0, testLength, (System.nanoTime() - startTime) / 1_000_000.0)));
        }
    }

    private static int oclEnqueueReadBuffer(long command_queue, long buffer, boolean blocking_read, long offset, LongBuffer ptr, PointerBuffer event_wait_list, PointerBuffer event) {
        if (CHECKS) {
            checkSafe(event, 1);
        }
        return nclEnqueueReadBuffer(command_queue, buffer, blocking_read ? 1 : 0, offset, Integer.toUnsignedLong(ptr.remaining()) << 3, memAddress(ptr), remainingSafe(event_wait_list), memAddressSafe(event_wait_list), memAddressSafe(event));
    }

    private void log(String message) {
        System.out.printf("[%d] %s\n", serial, message);
    }

    @Override
    public CompletableFuture<SimulationResult> runSimulation() {
        DeviceManager.clThread.callBacks.add(this::submitTask);
        return future;
    }

    private double getReductionRestoringMultiplier() {
        return switch (reductionMode) {
            case NONE -> 1.0D;
            case REDUCED_KELP_COUNT -> kelpCount / 256.0;
            case REDUCED_TEST_LENGTH -> testLength / (double) (harvestPeriod + waterFlowDelay);
            case REDUCED_BOTH -> (kelpCount / 256.0) * (testLength / (double) (harvestPeriod + waterFlowDelay));
        };
    }

    public enum ReductionMode {
        NONE,
        REDUCED_TEST_LENGTH,
        REDUCED_KELP_COUNT,
        REDUCED_BOTH
    }

}
