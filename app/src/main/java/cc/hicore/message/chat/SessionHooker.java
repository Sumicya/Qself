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

package cc.hicore.message.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import cc.hicore.QApp.QAppUtils;
import cc.hicore.ReflectUtil.XField;
import cc.hicore.hook.RepeaterPlus;
import com.google.common.collect.Lists;
import io.github.qauxv.base.ITraceableDynamicHook;
import io.github.qauxv.base.RuntimeErrorTracer;
import io.github.qauxv.base.annotation.EntityAgentEntry;
import io.github.qauxv.base.annotation.FunctionHookEntry;
import io.github.qauxv.hook.BaseHookDispatcher;
import io.github.qauxv.router.dispacher.InputButtonHookDispatcher;
import io.github.qauxv.util.Initiator;
import io.github.qauxv.util.dexkit.AIO_Create_QQNT;
import io.github.qauxv.util.dexkit.AIO_Destroy_QQNT;
import io.github.qauxv.util.dexkit.DexKit;
import io.github.qauxv.util.dexkit.DexKitTarget;
import io.github.qauxv.util.xpcompat.XC_MethodHook;
import io.github.qauxv.util.xpcompat.XposedBridge;
import java.util.List;
import java.util.Stack;

@EntityAgentEntry
@FunctionHookEntry
public class SessionHooker extends BaseHookDispatcher<SessionHooker.IAIOParamUpdate> {

    public static final SessionHooker INSTANCE = new SessionHooker();

    private SessionHooker() {
        super(new DexKitTarget[]{
                AIO_Create_QQNT.INSTANCE,
                AIO_Destroy_QQNT.INSTANCE
        });
    }

    private static final SessionHooker.IAIOParamUpdate[] DECORATORS = {
            RepeaterPlus.INSTANCE,
            InputButtonHookDispatcher.INSTANCE
    };

    @NonNull
    @Override
    public SessionHooker.IAIOParamUpdate[] getDecorators() {
        return DECORATORS;
    }

    private static final Stack<Object> aioParams = new Stack<>();

    @Override
    protected boolean initOnce() throws Exception {
        XposedBridge.hookMethod(DexKit.loadMethodFromCache(AIO_Create_QQNT.INSTANCE), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object pie = param.thisObject;
                Object AIOParam = XField.obj(pie).type(Initiator.loadClass("com.tencent.aio.data.AIOParam")).get();
                aioParams.push(AIOParam);
                for (SessionHooker.IAIOParamUpdate decorator : getDecorators()) {
                    decorator.onAIOParamUpdate(AIOParam);
                }
            }
        });
        XposedBridge.hookMethod(DexKit.loadMethodFromCache(AIO_Destroy_QQNT.INSTANCE), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!aioParams.empty()) {
                    aioParams.pop();
                }
                if (!aioParams.empty()) {
                    for (SessionHooker.IAIOParamUpdate decorator : getDecorators()) {
                        decorator.onAIOParamUpdate(aioParams.peek());
                    }
                }
            }
        });
        return true;
    }

    @Override
    public boolean isAvailable() {
        return QAppUtils.isQQnt();
    }

    @Override
    public boolean isEnabled() {
        return QAppUtils.isQQnt() && super.isEnabled();
    }

    public interface IAIOParamUpdate extends ITraceableDynamicHook {

        void onAIOParamUpdate(Object AIOParam);

        @Nullable
        @Override
        default List<RuntimeErrorTracer> getRuntimeErrorDependentComponents() {
            return Lists.asList(SessionHooker.INSTANCE, RuntimeErrorTracer.EMPTY_ARRAY);
        }
    }
}
