/*
 * Copyright (c) 2012-present NAVER Corp.
 *
 * This file is part of The nGrinder software distribution. Refer to
 * the file LICENSE which is part of The nGrinder distribution for
 * licensing details. The nGrinder distribution is available on the
 * Internet at https://naver.github.io/ngrinder
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.common.util;

import org.ngrinder.monitor.share.domain.BandWidth;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.List;

import static com.sun.jna.Platform.isWindows;
import static java.lang.System.currentTimeMillis;
import static oshi.util.ExecutingCommand.runNative;

public class SystemInfoUtils {

	private static HardwareAbstractionLayer hardware;
	private static OperatingSystem operatingSystem;

	// For calculate current cpu load.
	private static long[] prevTicks;

	private static List<NetworkIF> networkIFs;

	static {
		SystemInfo systemInfo = new SystemInfo();
		operatingSystem = systemInfo.getOperatingSystem();
		hardware = systemInfo.getHardware();
		networkIFs = hardware.getNetworkIFs();
		prevTicks = systemInfo.getHardware().getProcessor().getSystemCpuLoadTicks();
	}

	public static OSProcess getProcess(int pid) {
		return operatingSystem.getProcess(pid);
	}

	public static List<String> killProcess(int pid) {
		return isWindows() ? runNative("taskkill /f /pid " + pid) : runNative("kill -9 " + pid);
	}

	public static int getPid() {
		return operatingSystem.getProcessId();
	}

	public static long getAvailableMemory() {
		return hardware.getMemory().getAvailable();
	}

	public static long getTotalMemory() {
		return hardware.getMemory().getTotal();
	}

	public static double getCpuUsedPercentage() {
		CentralProcessor processor = hardware.getProcessor();
		double cpuUsedPercentage = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
		prevTicks = processor.getSystemCpuLoadTicks();
		return cpuUsedPercentage;
	}

	public static BandWidth getNetworkUsage() {
		long rx = 0;
		long tx = 0;

		if (!networkIFs.isEmpty()) {
			for (NetworkIF net : networkIFs) {
				net.updateAttributes();
				rx += net.getBytesRecv();
				tx += net.getBytesSent();
			}
		}
		return getBandWidth(rx, tx);
	}

	private static BandWidth getBandWidth(long rx, long tx) {
		BandWidth bandWidth = new BandWidth(currentTimeMillis());
		bandWidth.setReceived(bandWidth.getReceived() + rx);
		bandWidth.setSent(bandWidth.getSent() + tx);
		return bandWidth;
	}
}
