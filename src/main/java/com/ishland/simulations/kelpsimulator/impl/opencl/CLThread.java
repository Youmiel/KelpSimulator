package com.ishland.simulations.kelpsimulator.impl.opencl;

import com.ishland.simulations.kelpsimulator.Config;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryStack;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;

import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.checkCLError;
import static com.ishland.simulations.kelpsimulator.impl.opencl.CLUtil.getDeviceInfoStringUTF8;
import static org.lwjgl.opencl.CL10.CL_DEVICE_NAME;
import static org.lwjgl.opencl.CL10.CL_DEVICE_VERSION;
import static org.lwjgl.opencl.CL10.CL_DRIVER_VERSION;
import static org.lwjgl.opencl.CL10.clBuildProgram;
import static org.lwjgl.opencl.CL10.clCreateCommandQueue;
import static org.lwjgl.opencl.CL10.clCreateProgramWithSource;
import static org.lwjgl.opencl.CL10.clFlush;
import static org.lwjgl.opencl.CL10.clReleaseProgram;
import static org.lwjgl.system.MemoryUtil.NULL;

public class CLThread extends Thread {

    private static final int platformOrdinal = Integer.parseInt(Config.implArgs[1]);
    private static final int deviceOrdinal = Integer.parseInt(Config.implArgs[2]);

    public volatile boolean running = true;

    final Queue<CallBack> callBacks = new ConcurrentLinkedQueue<>();

    @Override
    public void run() {
        final DeviceManager.PlatformDevices platformDevices = DeviceManager.validDevices.get(platformOrdinal);
        final long platform = platformDevices.platform();
        final long device = platformDevices.devices()[deviceOrdinal];
        System.out.println(String.format("Using device: %s version %s - %s", getDeviceInfoStringUTF8(device, CL_DEVICE_NAME), getDeviceInfoStringUTF8(device, CL_DEVICE_VERSION), getDeviceInfoStringUTF8(device, CL_DRIVER_VERSION)));
        try (final DeviceManager.OpenCLContext context = new DeviceManager.OpenCLContext(platform, device);
             final MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errCodeRet = stack.callocInt(1);

            // prepare program
            System.out.println("Preparing program");
            final String source;
            try (InputStream in = OpenCLSimulationSession.class.getClassLoader().getResourceAsStream("opencl/impl.cl")) {
                if (in == null) throw new FileNotFoundException("impl.cl");
                source = new String(in.readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            final long program = clCreateProgramWithSource(context.getContext(), source, errCodeRet);
            checkCLError(errCodeRet);
            checkCLError(clBuildProgram(program, context.getDevice(), "", null, NULL));

            final long queue = clCreateCommandQueue(context.getContext(), context.getDevice(), NULL, errCodeRet);
            checkCLError(errCodeRet);

            {
                while (running) {
                    CallBack callBack = callBacks.poll();
                    if (callBack != null) {
                        try {
                            callBack.call(context, program, queue);
                            checkCLError(clFlush(queue));
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    } else {
                        LockSupport.parkNanos("Waiting for tasks", 100_000);
                    }
                }
            }

            // clean up
            System.out.println("Cleaning up");
            checkCLError(clReleaseProgram(program));
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException(t);
        }
    }

    interface CallBack {

        void call(DeviceManager.OpenCLContext context, long program, long queue);

    }

}
