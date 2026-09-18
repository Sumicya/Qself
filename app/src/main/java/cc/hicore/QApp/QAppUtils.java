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

package cc.hicore.QApp;

import cc.hicore.ReflectUtil.XClass;
import cc.hicore.ReflectUtil.XMethod;
import io.github.qauxv.util.Initiator;

public class QAppUtils {
    public static long getServiceTime(){
        try {
            return XMethod.clz("com.tencent.mobileqq.msf.core.NetConnInfoCenter").name("getServerTimeMillis").ret(long.class).invoke();
        } catch (Exception e) {
            return 0;
        }
    }
    public static String UserUinToPeerID(String UserUin){
        try {
            Object convertHelper = XClass.newInstance(Initiator.loadClass("com.tencent.qqnt.kernel.api.impl.UixConvertAdapterApiImpl"));
            return XMethod.obj(convertHelper).name("getUidFromUin").ret(String.class).param(long.class).invoke(Long.parseLong(UserUin));
        }catch (Exception e){
            return "";
        }
    }
    public static boolean isQQnt(){
        try {
            return Initiator.load("com.tencent.qqnt.base.BaseActivity") != null;
        }catch (Exception e){
            return false;
        }

    }
    public static String getCurrentUin(){
        try {
            Object AppRuntime = getAppRuntime();
            return XMethod.obj(AppRuntime).name("getCurrentAccountUin").ret(String.class).invoke();
        } catch (Exception e) {
            return "";
        }
    }
    public static Object getAppRuntime() throws Exception {
        Object sApplication = XMethod.clz("com.tencent.common.app.BaseApplicationImpl").name("getApplication").ret(Initiator.load("com.tencent.common.app.BaseApplicationImpl")).invoke();
        return XMethod.obj(sApplication).name("getRuntime").ret(Initiator.loadClass("mqq.app.AppRuntime")).invoke();
    }
}
