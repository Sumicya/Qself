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

package cc.hicore.hook;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import cc.ioctl.util.HookUtils;
import io.github.qauxv.util.Reflex;
import io.github.qauxv.base.annotation.FunctionHookEntry;
import io.github.qauxv.base.annotation.UiItemAgentEntry;
import io.github.qauxv.dsl.FunctionEntryRouter;
import io.github.qauxv.hook.CommonSwitchFunctionHook;
import io.github.qauxv.util.Initiator;
import io.github.qauxv.util.dexkit.CPopOutEmoticonUtil;
import io.github.qauxv.util.dexkit.DexKit;
import io.github.qauxv.util.dexkit.DexKitTarget;
import java.util.Objects;

@FunctionHookEntry
@UiItemAgentEntry
public class DisablePopOutEmoticon extends CommonSwitchFunctionHook {

    public static final DisablePopOutEmoticon INSTANCE = new DisablePopOutEmoticon();

    private DisablePopOutEmoticon() {
        super(new DexKitTarget[]{CPopOutEmoticonUtil.INSTANCE});
    }

    @NonNull
    @Override
    public String getName() {
        return "禁止弹射表情";
    }

    @Nullable
    @Override
    public String getDescription() {
        return "去除好友界面长按小表情发送弹射表情";
    }

    @NonNull
    @Override
    public String[] getUiItemLocation() {
        return FunctionEntryRouter.Locations.Simplify.CHAT_EMOTICON;
    }

    @Override
    protected boolean initOnce() throws Exception {
        HookUtils.hookBeforeIfEnabled(this,
                Reflex.findSingleMethod(Objects.requireNonNull(DexKit.loadClassFromCache(CPopOutEmoticonUtil.INSTANCE), "C_PopOutEmoticonUtil"),
                        boolean.class, false,
                        int.class, Initiator.loadClass("com.tencent.mobileqq.emoticonview.EmoticonInfo"), int.class),
                param -> param.setResult(false));
        return true;
    }
}
