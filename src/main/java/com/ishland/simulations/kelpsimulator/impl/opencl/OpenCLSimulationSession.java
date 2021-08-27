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
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.checkCLError;
import static org.lwjgl.opencl.CL10.CL_COMPLETE;
import static org.lwjgl.opencl.CL10.CL_MEM_WRITE_ONLY;
import static org.lwjgl.opencl.CL10.CL_SUCCESS;
import static org.lwjgl.opencl.CL10.clCreateBuffer;
import static org.lwjgl.opencl.CL10.clCreateKernel;
import static org.lwjgl.opencl.CL10.clEnqueueNDRangeKernel;
import static org.lwjgl.opencl.CL10.clEnqueueReadBuffer;
import static org.lwjgl.opencl.CL10.clFlush;
import static org.lwjgl.opencl.CL10.clReleaseEvent;
import static org.lwjgl.opencl.CL10.clReleaseKernel;
import static org.lwjgl.opencl.CL10.clReleaseMemObject;
import static org.lwjgl.opencl.CL10.clSetKernelArg1i;
import static org.lwjgl.opencl.CL10.clSetKernelArg1l;
import static org.lwjgl.opencl.CL10.clSetKernelArg1p;
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

    private final Random random = new Random();
    private final CompletableFuture<SimulationResult> future = new CompletableFuture<>();

    public OpenCLSimulationSession(int randomTickSpeed, boolean schedulerFirst, int waterFlowDelay, int kelpCount, long testLength, int harvestPeriod, int heightLimit) {
        this.randomTickSpeed = randomTickSpeed;
        this.schedulerFirst = schedulerFirst;
        this.waterFlowDelay = waterFlowDelay;
        this.kelpCount = kelpCount;
        this.testLength = testLength;
        this.harvestPeriod = harvestPeriod;
        this.heightLimit = heightLimit;
    }

    public void submitTask(DeviceManager.OpenCLContext context, long program, long queue) {
        final long startTime = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errCodeRet = stack.callocInt(1);

            // prepare storage
//            log("Allocating memory resources");
            final int groupSize = (int) (testLength / harvestPeriod + 1);
            final long totalStoragePointer = clCreateBuffer(context.getContext(), CL_MEM_WRITE_ONLY, (long) kelpCount << 3, errCodeRet);
            checkCLError(errCodeRet);
            final long perHarvestStoragePointer = clCreateBuffer(context.getContext(), CL_MEM_WRITE_ONLY, ((long) kelpCount * groupSize) << 2, errCodeRet);
            checkCLError(errCodeRet);

            // prepare kernel
//            log("Preparing kernel");
            final long kernel = clCreateKernel(program, "doWork", errCodeRet);
            checkCLError(errCodeRet);
            checkCLError(clSetKernelArg1i(kernel, 0, randomTickSpeed));
            checkCLError(clSetKernelArg1i(kernel, 1, schedulerFirst ? 1 : 0));
            checkCLError(clSetKernelArg1i(kernel, 2, waterFlowDelay));
            checkCLError(clSetKernelArg1i(kernel, 3, kelpCount));
            checkCLError(clSetKernelArg1l(kernel, 4, testLength));
            checkCLError(clSetKernelArg1i(kernel, 5, harvestPeriod));
            checkCLError(clSetKernelArg1i(kernel, 6, heightLimit));
            checkCLError(clSetKernelArg1i(kernel, 7, groupSize)); // per harvest size
            checkCLError(clSetKernelArg1l(kernel, 8, new Random().nextLong())); // seed
            checkCLError(clSetKernelArg1p(kernel, 9, totalStoragePointer));
            checkCLError(clSetKernelArg1p(kernel, 10, perHarvestStoragePointer));

            // submit
//            log("Submitting to OpenCL");
            final PointerBuffer globalWorkSize = stack.callocPointer(1);
            globalWorkSize.put(kelpCount);
            globalWorkSize.position(0);
            final PointerBuffer eventPointer = stack.callocPointer(1);
            checkCLError(clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, null, null, eventPointer));
            final long eventptr = eventPointer.get(0);

            // waiting
//            log("Waiting for results");
            final CLEventCallback[] clEventCallback = new CLEventCallback[1];
            clEventCallback[0] = CLEventCallback.create((event, event_command_exec_status, user_data) -> {
                if (event_command_exec_status == CL_COMPLETE) {
                    DeviceManager.clThread.callBacks.add((unused, unused1, unused2) -> {
                        try {
//                            log("Reading results from OpenCL");
                            final LongBuffer totalStorage = MemoryUtil.memAllocLong(kelpCount);
                            final IntBuffer perHarvestStorage = MemoryUtil.memAllocInt(kelpCount * groupSize);
                            checkCLError(clFlush(queue));
                            checkCLError(oclEnqueueReadBuffer(queue, totalStoragePointer, true, 0, totalStorage, null, null));
                            checkCLError(clEnqueueReadBuffer(queue, perHarvestStoragePointer, true, 0, perHarvestStorage, null, null));

                            // process result
//                            log("Processing results");
                            totalStorage.position(0);
                            final long[] totalStorageArray = new long[totalStorage.remaining()];
                            while (totalStorage.hasRemaining()) {
                                totalStorageArray[totalStorage.position()] = totalStorage.get();
                            }

                            perHarvestStorage.position(0);
                            final IntStream.Builder perHarvestStream = IntStream.builder();
                            for (int group = 0; group < kelpCount; group++) {
                                final int size = perHarvestStorage.get(group * groupSize + groupSize - 1);
                                if (size == 1 << 31) {
                                    log(String.format("Group %d encountered a OutOfBounds issue, ignoring group", size));
                                }
                                for (int i = 0; i < size; i++) {
                                    perHarvestStream.accept(perHarvestStorage.get(group * groupSize + i));
                                }
                            }

                            future.complete(new SimulationResult(Arrays.stream(totalStorageArray).summaryStatistics(), perHarvestStream.build().summaryStatistics()));

//                            log("Cleaning up");
                            checkCLError(clReleaseEvent(eventptr));
                            clEventCallback[0].free();
                            checkCLError(clReleaseKernel(kernel));
                            checkCLError(clReleaseMemObject(totalStoragePointer));
                            checkCLError(clReleaseMemObject(perHarvestStoragePointer));
                            MemoryUtil.memFree(totalStorage);
                            MemoryUtil.memFree(perHarvestStorage);
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
}