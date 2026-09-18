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

package cc.hicore.Utils;

import io.github.qauxv.util.Log;

public class XLog {
    public static void e(String TAG,Throwable msg){
        Log.e("[QAuxv]"+"("+TAG+")"+Log.getStackTraceString(msg));
    }
    public static void e(String TAG,String TAG2,Throwable msg){
        e(TAG+"."+TAG2,msg);
    }
    public static void e(String TAG,String msg){
        Log.e("[QAuxv]"+"("+TAG+")"+msg);
    }
    public static void d(String TAG,String msg){
        Log.d("[QAuxv]"+"("+TAG+")"+msg);
    }
}
