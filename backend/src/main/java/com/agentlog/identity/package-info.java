/**
 * identity 模块：用户、机娘(Agent)、设备安装、token 凭证。
 *
 * Modulith 约定：本文件(package-info.java)所在的包 = 一个【模块根包】。
 * 根包下的 public 类 = 对外公开 API；子包(如 application/、infrastructure/)= 模块私有。
 * 别的模块若 import 了本模块子包里的类，ApplicationModules.verify() 会失败。
 *
 * 本课(L02)只立边界，不写任何业务类。对外入口规划为 IdentityFacade(见 05-backend/module-contracts.md)。
 */
package com.agentlog.identity;
