package com.ishland.simulations.kelpsimulator.impl.opencl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.Version;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CLCapabilities;
import org.lwjgl.opencl.CLContextCallback;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

import java.io.Closeable;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;

import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.checkCLError;
import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.getDeviceInfoStringUTF8;
import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.getPlatformInfoStringUTF8;
import static org.lwjgl.opencl.CL10.CL_CONTEXT_PLATFORM;
import static org.lwjgl.opencl.CL10.CL_DEVICE_NAME;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_ALL;
import static org.lwjgl.opencl.CL10.CL_DEVICE_VERSION;
import static org.lwjgl.opencl.CL10.CL_DRIVER_VERSION;
import static org.lwjgl.opencl.CL10.CL_PLATFORM_NAME;
import static org.lwjgl.opencl.CL10.CL_PLATFORM_VERSION;
import static org.lwjgl.opencl.CL10.clCreateContext;
import static org.lwjgl.opencl.CL10.clGetDeviceIDs;
import static org.lwjgl.opencl.CL10.clGetPlatformIDs;
import static org.lwjgl.opencl.CL10.clReleaseContext;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memUTF8;

public class DeviceManager {

    public static final List<PlatformDevices> validDevices = new ArrayList<>();
    static final CLThread clThread = new CLThread();

    static {
        System.out.println("LWJGL version " + Version.getVersion());
        try (MemoryStack stack = stackPush()) {
            final IntBuffer pi = stack.mallocInt(1);
            checkCLError(clGetPlatformIDs(null, pi));

            PointerBuffer platforms = stack.mallocPointer(pi.get(0));
            checkCLError(clGetPlatformIDs(platforms, (IntBuffer) null));

            final PointerBuffer ctxProps = stack.mallocPointer(3);
            ctxProps.put(0, CL_CONTEXT_PLATFORM).put(2, 0);

            IntBuffer errCodeRet = stack.callocInt(1);
            for (int p = 0; p < platforms.capacity(); p++) {
                final long platform = platforms.get(p);
                ctxProps.put(1, platform);

                CLCapabilities platformCaps = CL.createPlatformCapabilities(platform);
                System.out.printf("Found platform %d: %s version %s\n", validDevices.size(), getPlatformInfoStringUTF8(platform, CL_PLATFORM_NAME), getPlatformInfoStringUTF8(platform, CL_PLATFORM_VERSION));

                checkCLError(clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, null, pi));

                PointerBuffer devices = stack.mallocPointer(pi.get(0));
                checkCLError(clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, devices, (IntBuffer)null));

                final LongStream.Builder devicePointers = LongStream.builder();
                int deviceNum = 0;

                for (int d = 0; d < devices.capacity(); d++) {
                    long device = devices.get(d);

                    CLCapabilities caps = CL.createDeviceCapabilities(device, platformCaps);

                    if (!caps.OpenCL11) continue;

                    System.out.printf("Found device %d: %s version %s - %s\n", deviceNum, getDeviceInfoStringUTF8(device, CL_DEVICE_NAME), getDeviceInfoStringUTF8(device, CL_DEVICE_VERSION), getDeviceInfoStringUTF8(device, CL_DRIVER_VERSION));

                    try {
                        CLContextCallback contextCB;
                        long context = clCreateContext(ctxProps, device, contextCB = CLContextCallback.create((errinfo, private_info, cb, user_data) -> {
                            System.err.println("[LWJGL] cl_context_callback");
                            System.err.println("\tInfo: " + memUTF8(errinfo));
                        }), NULL, errCodeRet);
                        checkCLError(errCodeRet);

                        checkCLError(clReleaseContext(context));
                        contextCB.free();
                        devicePointers.accept(device);
                        deviceNum ++;
                    } catch (Throwable t) {
                        System.err.println("Error occurred while testing device");
                        t.printStackTrace();
                    }
                }
                validDevices.add(new PlatformDevices(platform, devicePointers.build().toArray()));
            }
        }
        clThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> clThread.running = false));
    }



    public static class OpenCLContext implements Closeable {
        private final long platform;
        private final long device;
        private final long context;
        private final CLContextCallback contextCB;

        public OpenCLContext(long platform, long device) {
            this.platform = platform;
            this.device = device;
            try (MemoryStack stack = stackPush()) {
                IntBuffer errCodeRet = stack.callocInt(1);
                PointerBuffer ctxProps = stack.mallocPointer(3);
                ctxProps
                        .put(0, CL_CONTEXT_PLATFORM)
                        .put(1, platform)
                        .put(2, 0);
                this.context = clCreateContext(ctxProps, device, contextCB = CLContextCallback.create((errinfo, private_info, cb, user_data) -> {
                    System.err.println("[LWJGL] OpenCL error: " + memUTF8(errinfo));
                }), NULL, errCodeRet);
                checkCLError(errCodeRet);
            }
        }

        public long getContext() {
            return context;
        }

        public long getPlatform() {
            return platform;
        }

        public long getDevice() {
            return device;
        }

        @Override
        public void close() {
            checkCLError(clReleaseContext(context));
            contextCB.free();
        }
    }

    public static final class PlatformDevices {
        private final long platform;
        private final long[] devices;

        public PlatformDevices(long platform, long[] devices) {
            this.platform = platform;
            this.devices = devices;
        }

        public long platform() {
            return platform;
        }

        public long[] devices() {
            return devices;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (PlatformDevices) obj;
            return this.platform == that.platform &&
                    Objects.equals(this.devices, that.devices);
        }

        @Override
        public int hashCode() {
            return Objects.hash(platform, devices);
        }

        @Override
        public String toString() {
            return "PlatformDevices[" +
                    "platform=" + platform + ", " +
                    "devices=" + devices + ']';
        }

    }

}
