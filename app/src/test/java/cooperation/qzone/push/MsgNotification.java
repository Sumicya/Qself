/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package cooperation.qzone.push;

import android.content.Context;
import java.util.HashMap;

/**
 * Test-only host fixture bearing the real (unobfuscated) host FQN. Exercises
 * both adapter analyses: the entry must be picked by the widest-void-method
 * heuristic among the decoys, and descArgIndex must land on the SECOND
 * String parameter (uin first, description second).
 */
@SuppressWarnings("unused")
public class MsgNotification {

    /** decoy: void but fewer parameters than the entry */
    public void notify(Context context, String title) {
    }

    /** decoy: widest of all but not void */
    public int notifyResult(Context context, String uin, String desc, int type,
            HashMap<String, String> extras, long seq) {
        return 0;
    }

    /** the entry: widest void method; second String sits at index 2 */
    public void showNotification(Context context, String uin, String desc, int type,
            HashMap<String, String> extras) {
    }
}
