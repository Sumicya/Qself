/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2023 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.qauxv.bridge.ntapi;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.github.qauxv.util.Reflex;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService;
import io.github.qauxv.bridge.kernelcompat.KernelMsgServiceCompat;
import io.github.qauxv.util.Initiator;
import java.lang.reflect.Method;
import mqq.app.AppRuntime;
import mqq.app.api.IRuntimeService;

public class MsgServiceHelper {

    private MsgServiceHelper() {
    }

    @NonNull
    public static Object getMsgService(@NonNull AppRuntime app) throws ReflectiveOperationException, LinkageError {
        // IMsgService msgService = ((IKernelService) app.getRuntimeService(IKernelService.class, "")).getMsgService();
        Class<? extends IRuntimeService> kIKernelService = (Class<? extends IRuntimeService>) Initiator.loadClass("com.tencent.qqnt.kernel.api.IKernelService");
        IRuntimeService kernelService = app.getRuntimeService(kIKernelService, "");
        Method getMsgService = kernelService.getClass().getMethod("getMsgService");
        return getMsgService.invoke(kernelService);
    }

    @Nullable
    public static IKernelMsgService getKernelMsgServiceRaw(@NonNull AppRuntime app) throws ReflectiveOperationException, LinkageError {
        Object msgService = getMsgService(app);
        IKernelMsgService service;
        try {
            // 8.9.78起
            service = (IKernelMsgService) msgService.getClass().getMethod("getService").invoke(msgService);
        } catch (Exception unused) {
            Method getKMsgSvc = Reflex.findSingleMethod(msgService.getClass(), IKernelMsgService.class, false);
            service = (IKernelMsgService) getKMsgSvc.invoke(msgService);
        }
        return service;
    }

    @Nullable
    public static KernelMsgServiceCompat getKernelMsgService(@NonNull AppRuntime app) throws ReflectiveOperationException, LinkageError {
        IKernelMsgService service = getKernelMsgServiceRaw(app);
        if (service != null) {
            return new KernelMsgServiceCompat(service);
        } else {
            return null;
        }
    }

}
