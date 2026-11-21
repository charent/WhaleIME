/**
 * This file is part of pinyin4j (http://sourceforge.net/projects/pinyin4j/) and distributed under
 * GNU GENERAL PUBLIC LICENSE (GPL).
 * 
 * pinyin4j is free software; you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 * 
 * pinyin4j is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with pinyin4j.
 */

package com.whale.ime.vendor.pinyin4j;

import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.IOException;

/**
 * 资源读取入口。表文件在 host 的 assets/pinyindb/ 下，
 * 由宿主在启动时通过 {@link PinyinHelper#init(AssetManager)} 注入，
 * 本包不允许直接依赖宿主代码。
 *
 * 本地改动（相对上游 pinyin4j）：上游从 classpath 读 resource，这里改为读 AssetManager。
 */
class ResourceHelper {

  private static volatile AssetManager sAssets;

  static void init(AssetManager assets) {
    sAssets = assets;
  }

  /**
   * @param resourceName
   * @return resource (mainly file in file system or file in compressed
   *         package) as BufferedInputStream
   */
  static BufferedInputStream getResourceInputStream(String resourceName) throws IOException {
    AssetManager assets = sAssets;
    if (assets == null) {
      throw new IllegalStateException(
          "pinyin4j 未初始化：请在任何 PinyinHelper 调用之前执行 "
              + "PinyinHelper.init(Launcher.instance.context.assets)");
    }
    return new BufferedInputStream(assets.open(resourceName));
  }
}
