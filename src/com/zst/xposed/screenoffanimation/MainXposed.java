package com.zst.xposed.screenoffanimation;

import com.zst.xposed.screenoffanimation.anim.*;
import com.zst.xposed.screenoffanimation.helpers.Utils;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.XModuleResources;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainXposed implements IXposedHookZygoteInit, IXposedHookLoadPackage {
	
	public static boolean mDontAnimate;

	static XModuleResources sModRes;
	static XSharedPreferences sPref;
	
	static Context mContext;
	static WindowManager mWm;
	
	static int mScreenOffAnimIndex = Common.Pref.Def.OFF_EFFECT;
	static int mScreenOffAnimSpeed = Common.Pref.Def.OFF_SPEED;
	
	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		sModRes = XModuleResources.createInstance(startupParam.modulePath, null);
		sPref = new XSharedPreferences(Common.PACKAGE_THIS, Common.Pref.PREF_MAIN);
	}
	
	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (lpparam.packageName.equals(Common.PACKAGE_THIS)) {
			hookMainActivity(lpparam);
			return;
		}
		if (!lpparam.packageName.equals("android")) return;
		
		refreshSettings();
		
		try { // late Android 4.2.1 onwards (built after Aug 15, 2012)
			final Class<?> hookClass = XposedHelpers.findClass(
					"com.android.server.power.PowerManagerService", lpparam.classLoader);
			XposedBridge.hookAllMethods(hookClass, "goToSleepInternal", sScreenOffHook);
			XposedBridge.hookAllMethods(hookClass, "goToSleepNoUpdateLocked", sScreenOffHook);
			XposedBridge.hookAllMethods(hookClass, "init", sInitHook);
			hookDisableNativeScreenOffAnim(lpparam);
			Utils.log("Done hooks for PowerManagerService (New Package)");
		} catch (Throwable e) {
			// Android 4.0 to Android 4.2.1 (built before Aug 15, 2012)
			// https://github.com/android/platform_frameworks_base/commit/9630704ed3b265f008a8f64ec60a33cf9dcd3345
			final Class<?> hookClass = XposedHelpers.findClass(
					"com.android.server.PowerManagerService", lpparam.classLoader);
			XposedBridge.hookAllMethods(hookClass, "setPowerState", sScreenOffHook);
			XposedBridge.hookAllMethods(hookClass, "init", sInitHook);
			Utils.log("Done hooks for PowerManagerService (Old Package)");
		}
	}
	
	private final XC_MethodHook sInitHook = new XC_MethodHook() {
		@Override
		protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
			mContext = (Context) param.args[0];
			mWm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
			installBroadcast();
		}
	};
	
	private final XC_MethodReplacement sScreenOffHook = new XC_MethodReplacement() {
		@Override
		protected Object replaceHookedMethod(final MethodHookParam param) throws Throwable {
			if (param.method.getName().equals("setPowerState")) {
				// Android 4.0 to Android 4.2.1 (built before Aug 15, 2012)
				if ((Integer) param.args[0] != 0)
					return Utils.callOriginal(param);
			} else if (param.method.getName().equals("goToSleepNoUpdateLocked")) {
				// reason != GO_TO_SLEEP_REASON_TIMEOUT
				if ((Integer) param.args[1] != 2)
					return Utils.callOriginal(param);
				
				if (!Utils.isValidSleepEvent(param.thisObject, (Long) param.args[0]))
					return false;
			}
			
			if (mScreenOffAnimIndex == Common.Anim.UNKNOWN || mDontAnimate)
				return Utils.callOriginal(param);
			
			if (mContext == null) {
				// If the context cannot be retrieved from the init method,
				try {
					mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
					mWm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
					installBroadcast();
				} catch (Exception e){
					Utils.log("Context cannot be retrieved (backup method failed) - " + e.toString());
					e.printStackTrace();
				}
			}
			
			AnimImplementation anim = findAnimation(mScreenOffAnimIndex);
			if (anim != null) {
				try {
					anim.anim_speed = mScreenOffAnimSpeed;
					anim.animateScreenOffWithHandler(mContext, mWm, param, sModRes);
				} catch (Exception e) {
					// So we don't crash system.
					Utils.toast(mContext, sModRes.getString(R.string.error_animating));
					Utils.log("Error with animateOnHandler", e);
				}
			} else {
				return Utils.callOriginal(param);
			}
			
			if (param.method.getName().equals("goToSleepNoUpdateLocked")) {
				return true;
			} else {
				return null;
			}
		}
	};
	
	private void hookMainActivity(LoadPackageParam lpp) {
		final Class<?> cls = XposedHelpers.findClass(MainActivity.class.getName(), lpp.classLoader);
		XposedBridge.hookAllMethods(cls, "isXposedRunning",
				XC_MethodReplacement.returnConstant(true));
	}
	
	private void hookDisableNativeScreenOffAnim(LoadPackageParam lpp) {
		try {
			final Class<?> cls = XposedHelpers.findClass("com.android.server.power.ElectronBeam",
					lpp.classLoader);
			XposedHelpers.findAndHookMethod(cls, "prepare", int.class,
					XC_MethodReplacement.returnConstant(false));
		} catch (Exception e) {
			Utils.log("Attempt to remove native screen off animation failed - " + e.toString());
			// MethodNotFoundException
		}
	}
	
	/**
	 * Registers the broadcast for refreshing and testing the settings
	 */
	private void installBroadcast() {
		IntentFilter filter = new IntentFilter();
		filter.addAction(Common.BROADCAST_REFRESH_SETTINGS);
		filter.addAction(Common.BROADCAST_TEST_OFF_ANIMATION);
		filter.addAction(Common.BROADCAST_TEST_ON_ANIMATION);
		filter.addAction(Intent.ACTION_SCREEN_ON);
		
		mContext.registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context c, Intent i) {
				if (i.getAction().equals(Common.BROADCAST_TEST_OFF_ANIMATION)) {
					final int anim_id = i.getIntExtra(Common.EXTRA_TEST_ANIMATION,
							Common.Pref.Def.OFF_EFFECT);
					AnimImplementation anim = findAnimation(anim_id);
					if (anim != null) {
						try {
							anim.anim_speed = mScreenOffAnimSpeed;
							anim.animateScreenOffWithHandler(mContext, mWm, null, sModRes);
						} catch (Exception e) {
							// So we don't crash system.
							Utils.toast(mContext, sModRes.getString(R.string.error_animating));
						}
					}
				} else if (i.getAction().equals(Common.BROADCAST_TEST_ON_ANIMATION)) {
					//TODO screen on animation
				} else if (i.getAction().equals(Intent.ACTION_SCREEN_ON)) {
					Utils.logcat("SCREEN ON");
				} else if (i.getAction().equals(Common.BROADCAST_REFRESH_SETTINGS)) {
					refreshSettings();
				}
			}
		}, filter);
	}
	
	private AnimImplementation findAnimation(int id) {
		switch (id) {
		case Common.Anim.UNKNOWN:
			return null;
		case Common.Anim.FADE:
			return new FadeOut();
		case Common.Anim.CRT:
			return new CRT();
		case Common.Anim.CRT_VERTICAL:
			return new CRTVertical();
		case Common.Anim.SCALE:
			return new ScaleDown();
		case Common.Anim.TV_BURN:
			return new TVBurnIn();
		case Common.Anim.LG_OPTIMUS_G:
			return new LGOptimusG();
		case Common.Anim.FADE_TILES:
			return new FadeTiles();
		default:
			return null;
		}
	}
	
	private void refreshSettings() {
		sPref.reload();
		
		mScreenOffAnimIndex = sPref.getInt(Common.Pref.Key.OFF_EFFECT, Common.Pref.Def.OFF_EFFECT);
		mScreenOffAnimSpeed = sPref.getInt(Common.Pref.Key.OFF_SPEED, Common.Pref.Def.OFF_SPEED);
	}
}
