package com.ishland.simulations.kelpsimulator.impl.opencl;

import com.ishland.simulations.kelpsimulator.impl.SimulationResult;
import com.ishland.simulations.kelpsimulator.impl.Simulator;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL11;
import org.lwjgl.system.MemoryStack;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.checkCLError;
import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.getDeviceInfoStringUTF8;
import static org.lwjgl.opencl.CL10.CL_DEVICE_NAME;
import static org.lwjgl.opencl.CL10.CL_DEVICE_VERSION;
import static org.lwjgl.opencl.CL10.CL_DRIVER_VERSION;
import static org.lwjgl.opencl.CL10.CL_MEM_WRITE_ONLY;
import static org.lwjgl.opencl.CL10.clBuildProgram;
import static org.lwjgl.opencl.CL10.clCreateBuffer;
import static org.lwjgl.opencl.CL10.clCreateCommandQueue;
import static org.lwjgl.opencl.CL10.clCreateKernel;
import static org.lwjgl.opencl.CL10.clCreateProgramWithSource;
import static org.lwjgl.opencl.CL10.clEnqueueNDRangeKernel;
import static org.lwjgl.opencl.CL10.clEnqueueReadBuffer;
import static org.lwjgl.opencl.CL10.clFlush;
import static org.lwjgl.opencl.CL10.clReleaseCommandQueue;
import static org.lwjgl.opencl.CL10.clReleaseKernel;
import static org.lwjgl.opencl.CL10.clReleaseMemObject;
import static org.lwjgl.opencl.CL10.clReleaseProgram;
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

    static {

    }

    private final int serial = GLOBAL_SERIAL.getAndIncrement();
    private final int randomTickSpeed;
    private final boolean schedulerFirst;
    private final int waterFlowDelay;
    private final int kelpCount;
    private final long testLength;
    private final int harvestPeriod;
    private final int heightLimit;
    private final int platform;
    private final int device;

    private final Random random = new Random();

    public OpenCLSimulationSession(int randomTickSpeed, boolean schedulerFirst, int waterFlowDelay, int kelpCount, long testLength, int harvestPeriod, int heightLimit, int platform, int device) {
        this.randomTickSpeed = randomTickSpeed;
        this.schedulerFirst = schedulerFirst;
        this.waterFlowDelay = waterFlowDelay;
        this.kelpCount = kelpCount;
        this.testLength = testLength;
        this.harvestPeriod = harvestPeriod;
        this.heightLimit = heightLimit;
        this.platform = platform;
        this.device = device;
    }

    @Override
    public SimulationResult runSimulation() {
        final DeviceManager.PlatformDevices platformDevices = DeviceManager.validDevices.get(platform);
        final long platform = platformDevices.platform();
        final long device = platformDevices.devices()[this.device];
        log(String.format("Using device: %s version %s - %s\n", getDeviceInfoStringUTF8(device, CL_DEVICE_NAME), getDeviceInfoStringUTF8(device, CL_DEVICE_VERSION), getDeviceInfoStringUTF8(device, CL_DRIVER_VERSION)));
        try (final DeviceManager.OpenCLContext context = new DeviceManager.OpenCLContext(platform, device);
             final MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errCodeRet = stack.callocInt(1);

            // prepare storage
            log("Allocating memory resources");
            final int groupSize = (int) (testLength / harvestPeriod);
            final LongBuffer totalStorage = stack.mallocLong(kelpCount);
            final IntBuffer perHarvestStorage = stack.callocInt(kelpCount * groupSize);
            final long totalStoragePointer = oclCreateBuffer(context.getContext(), CL_MEM_WRITE_ONLY, totalStorage, errCodeRet);
            checkCLError(errCodeRet);
            final long perHarvestStoragePointer = clCreateBuffer(context.getContext(), CL_MEM_WRITE_ONLY, perHarvestStorage, errCodeRet);
            checkCLError(errCodeRet);

            // prepare program
            log("Preparing program");
            final String source;
            try (InputStream in = OpenCLSimulationSession.class.getResourceAsStream("impl.cl")) {
                if (in == null) throw new FileNotFoundException("impl.cl");
                source = new String(in.readAllBytes());
            } catch (IOException e) {
                try {
                    checkCLError(clReleaseMemObject(totalStoragePointer));
                } catch (Throwable t) {
                    e.addSuppressed(t);
                }
                try {
                    checkCLError(clReleaseMemObject(perHarvestStoragePointer));
                } catch (Throwable t) {
                    e.addSuppressed(t);
                }
                throw new RuntimeException(e);
            }
            final long program = clCreateProgramWithSource(context.getContext(), source, errCodeRet);
            checkCLError(errCodeRet);
            checkCLError(clBuildProgram(program, context.getDevice(), "", null, NULL));

            // prepare kernel
            log("Preparing kernel");
            final long kernel = clCreateKernel(program, "doWork", errCodeRet);
            checkCLError(errCodeRet);
            checkCLError(clSetKernelArg1i(kernel, 0, randomTickSpeed));
            checkCLError(clSetKernelArg1s(kernel, 1, (short) (schedulerFirst ? 1 : 0)));
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
            log("Submitting to OpenCL");
            final long queue = clCreateCommandQueue(context.getContext(), context.getDevice(), NULL, errCodeRet);
            checkCLError(errCodeRet);
            final LongBuffer globalWorkSize = stack.mallocLong(1);
            globalWorkSize.put(0, kelpCount);
            final PointerBuffer pointerBuffer = stack.mallocPointer(1);
            pointerBuffer.put(globalWorkSize);
            checkCLError(clEnqueueNDRangeKernel(queue, kernel, 1, null, pointerBuffer, null, null, null));

            // waiting
            log("Waiting for results");
            checkCLError(oclEnqueueReadBuffer(queue, totalStoragePointer, true, 0, totalStorage, null, null));
            checkCLError(clEnqueueReadBuffer(queue, perHarvestStoragePointer, true, 0, perHarvestStorage, null, null));

            // process result
            log("Processing results");
            totalStorage.position(0);
            final LongStream.Builder totalStorageStream = LongStream.builder();
            while (totalStorage.hasRemaining())
                totalStorageStream.accept(totalStorage.get());

            perHarvestStorage.position(0);
            final IntStream.Builder perHarvestStream = IntStream.builder();
            for (int group = 0; group < kelpCount; group ++) {
                final int size = perHarvestStorage.get(group * groupSize + kelpCount - 1);
                if (size == 1 << 31) {
                    log(String.format("Group %d encountered a OutOfBounds issue, ignoring group", size));
                }
                for (int i = 0; i < size; i ++) {
                    perHarvestStream.accept(perHarvestStorage.get(group * groupSize + i));
                }
            }

            // clean up
            log("Cleaning up");
            checkCLError(clFlush(queue));
            checkCLError(clReleaseCommandQueue(queue));
            checkCLError(clReleaseKernel(kernel));
            checkCLError(clReleaseProgram(program));
            checkCLError(clReleaseMemObject(totalStoragePointer));
            checkCLError(clReleaseMemObject(perHarvestStoragePointer));

            return new SimulationResult(totalStorageStream.build().summaryStatistics(), perHarvestStream.build().summaryStatistics());
        }
    }

    private static long oclCreateBuffer(long context, long flags, LongBuffer host_ptr, IntBuffer errcode_ret) {
        if (CHECKS) {
            checkSafe(errcode_ret, 1);
        }
        return CL11.nclCreateBuffer(context, flags, (long) host_ptr.remaining() << 4, memAddress(host_ptr), memAddressSafe(errcode_ret));
    }

    private static int oclEnqueueReadBuffer(long command_queue, long buffer, boolean blocking_read, long offset, LongBuffer ptr, PointerBuffer event_wait_list, PointerBuffer event) {
        if (CHECKS) {
            checkSafe(event, 1);
        }
        return nclEnqueueReadBuffer(command_queue, buffer, blocking_read ? 1 : 0, offset, Integer.toUnsignedLong(ptr.remaining()) << 4, memAddress(ptr), remainingSafe(event_wait_list), memAddressSafe(event_wait_list), memAddressSafe(event));
    }

    private void log(String message) {
        System.out.printf("[%d] %s\n", serial, message);
    }


}