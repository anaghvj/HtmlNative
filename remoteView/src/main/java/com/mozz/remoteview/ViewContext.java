package com.mozz.remoteview;

import android.content.Context;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.mozz.remoteview.code.Code;
import com.mozz.remoteview.code.LuaRunner;
import com.mozz.remoteview.code.logcat;
import com.mozz.remoteview.code.properties;
import com.mozz.remoteview.code.setParams;
import com.mozz.remoteview.code.toast;
import com.mozz.remoteview.common.MainHandler;
import com.mozz.remoteview.common.StrRunnable;
import com.mozz.remoteview.common.WefRunnable;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.lang.ref.WeakReference;
import java.util.Map;

/**
 * @author Yang Tao, 17/2/24.
 */

public interface ViewContext {
    Context getAndroidContext();

    void execute(String code);

    void executeNowWithoutException(String code);

    View findViewById(@NonNull String id);

    void onViewLoaded();

    void onViewCreate();

    void addVariable(String string, Object object);

    void updateVariable(String string, Object newValue);

    Object getVariable(String string);

    View put(String id, View value);

    String allIdTag();

}

final class ViewContextImpl implements ViewContext {

    private static boolean DEBUG = false;

    private static final String TAG = ViewContext.class.getSimpleName();

    private static final int ViewContextTag = 0x3 << 24;

    final Map<String, View> mViewSelector = new ArrayMap<>();

    private final VariablePool mPool = new VariablePool();

    private Globals mGlobals;

    private final RVModule mModule;

    private final Context mContext;

    private ViewContextImpl(RVModule module, Context context) {
        mModule = module;
        mContext = context;
    }

    public Context getAndroidContext() {
        return mContext;
    }

    public void addVariable(String string, Object object) {
        mPool.addVariable(string, object);
    }

    public void updateVariable(String string, Object newValue) {
        mPool.updateVariable(string, newValue);
    }

    public Object getVariable(String string) {
        return mPool.getVariable(string);
    }

    @Nullable
    public View put(String id, View value) {
        View before = mViewSelector.put(id, value);
        if (before != null) {
            Log.w(TAG, "Duplicated id " + id + ", before is " + before + ", current is " + value);
        }
        return before;
    }

    @Nullable
    public View findViewById(@NonNull String id) {
        return mViewSelector.get(id);
    }

    public boolean containsView(String id) {
        return mViewSelector.containsKey(id);
    }

    public void onViewLoaded() {

        callCreated();

    }

    public void onViewCreate() {

        initLuaRunner();

        initVariablePool();

        callCreate();
    }

    private void initVariablePool() {

    }

    private void callCreate() {
        Code create = mModule.mFunctionTable.retrieveReserved(FunctionTable.CREATE);
        if (create == null) return;
        execute(create);
    }

    private void callCreated() {
        Code created = mModule.mFunctionTable.retrieveReserved(FunctionTable.CREATED);
        if (created == null) return;
        execute(created);
    }

    private void initLuaRunner() {
        LuaRunner.getInstance().runLuaScript(new WefRunnable<ViewContext>(this) {
            @Override
            protected void runOverride(ViewContext viewContext) {
                if (viewContext == null) return;
                long time1 = SystemClock.currentThreadTimeMillis();
                mGlobals = LuaRunner.newGlobals();
                mGlobals.set("view", new setParams(viewContext));
                mGlobals.set("toast", new toast(viewContext.getAndroidContext()));
                mGlobals.set("property", new properties.property(viewContext));
                mGlobals.set("setProperty", new properties.setProperty(viewContext));
                mGlobals.set("getProperty", new properties.getProperty(viewContext));
                mGlobals.set("log", new logcat());
                Log.i(TAG, "init Lua module spend " + (SystemClock.currentThreadTimeMillis() - time1) + " ms");
            }
        });

    }

    public String allIdTag() {
        return mViewSelector.toString();
    }

    public static ViewContext getViewContext(FrameLayout v) {
        Object object = v.getTag(ViewContextTag);

        if (object != null && object instanceof ViewContext) {
            return (ViewContext) object;
        }

        return null;
    }


    private void execute(Code code) {
        execute(code.toString());
    }

    @Override
    public void execute(final String code) {
        LuaRunner.getInstance().runLuaScript(new StrRunnableContext(this, code) {
            @Override
            protected void runOverride(String s) {
                ViewContext context = mContextRef.get();
                if (context == null) return;

                context.executeNowWithoutException(s);
            }
        });
    }

    @Override
    public void executeNowWithoutException(String s) {
        try {
            LuaValue l = mGlobals.load(s);
            l.call();
        } catch (final LuaError e) {
            // make sure that lua script dose not crash the whole app
            e.printStackTrace();
            Log.e(TAG, "LuaScriptRun");
            if (DEBUG)
                MainHandler.instance().post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getAndroidContext(), "LuaScript Wrong:\n" + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
        }
    }

    static ViewContext initViewContext(FrameLayout layout, RVModule module, Context context) {
        ViewContext v = new ViewContextImpl(module, context);
        layout.setTag(ViewContextTag, v);
        return v;
    }

    private static abstract class StrRunnableContext extends StrRunnable<String> {

        WeakReference<ViewContext> mContextRef;

        StrRunnableContext(ViewContext context, String s) {
            super(s);

            mContextRef = new WeakReference<>(context);
        }
    }
}
