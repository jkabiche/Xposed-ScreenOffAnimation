package com.zst.xposed.screenoffanimation.anim;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;

public class FadeOut extends ScreenOffAnim.Implementation {
	@Override
	public void animate(final Context ctx, WindowManager wm, MethodHookParam param, Resources res) {
		final View outline = new View(ctx);
		outline.setBackgroundColor(Color.BLACK);
		
		final Animation fadeIn = new AlphaAnimation(0, 1);
		fadeIn.setDuration(anim_speed);
		
		final ScreenOffAnim holder = new ScreenOffAnim(ctx, wm, param) {
			@Override
			public void animateView() {
				outline.startAnimation(fadeIn);
			}
		};
		fadeIn.setAnimationListener(new Animation.AnimationListener() {
			@Override
			public void onAnimationEnd(Animation a) {
				holder.mFrame.setBackgroundColor(Color.BLACK);
				finish(ctx, holder, 100);
			}
			@Override
			public void onAnimationStart(Animation a) {}
			@Override
			public void onAnimationRepeat(Animation a) {}
		});
		holder.show(outline);
	}
}
