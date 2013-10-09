package com.squeed.chromecast.hipstacaster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.ProviderInfo;
import android.support.v7.media.MediaRouter.RouteInfo;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.widget.GridView;
import android.widget.TextView;

import com.google.cast.ApplicationChannel;
import com.google.cast.ApplicationMetadata;
import com.google.cast.ApplicationSession;
import com.google.cast.CastContext;
import com.google.cast.CastDevice;
import com.google.cast.MediaRouteAdapter;
import com.google.cast.MediaRouteHelper;
import com.google.cast.MediaRouteStateChangeListener;
import com.google.cast.SessionError;
import com.squeed.chromecast.hipstacaster.dto.Photo;
import com.squeed.chromecast.hipstacaster.grid.GridViewAdapter;
import com.squeed.chromecast.hipstacaster.grid.ImageItem;
import com.squeed.chromecast.hipstacaster.img.Callback;
import com.squeed.chromecast.hipstacaster.img.DrawableManager;
import com.squeed.chromecast.hipstacaster.rest.LoadImageListTask;

public class HipstaActivity extends ActionBarActivity implements MediaRouteAdapter {

    private static final String TAG = HipstaActivity.class.getSimpleName();
    private static final com.google.cast.Logger sLog = new com.google.cast.Logger(TAG, true);
    private static final String APP_NAME = "HipstaCaster";

    private ApplicationSession mSession;
    private SessionListener mSessionListener;
    private CustomHipstaCasterStream mMessageStream;

    private GridView gridView;
    private GridViewAdapter customGridAdapter;
    
    private TextView mInfoView;
    
    private CastContext mCastContext;
    private CastDevice mSelectedDevice;
    private MediaRouter mMediaRouter;
    private MediaRouteSelector mMediaRouteSelector;
    private MediaRouter.Callback mMediaRouterCallback;


    private DrawableManager drawableManager;


    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.main);
        mInfoView = (TextView) findViewById(R.id.status);
        mInfoView.setText("Loading...");
        drawableManager = new DrawableManager();
        
        new LoadImageListTask(this).execute("sarek");

        mSessionListener = new SessionListener();
        mMessageStream = new CustomHipstaCasterStream();

        mCastContext = new CastContext(getApplicationContext());
        MediaRouteHelper.registerMinimalMediaRouteProvider(mCastContext, this);
        
        mMediaRouter = MediaRouter.getInstance(getApplicationContext());
//        mMediaRouteSelector = MediaRouteHelper.buildMediaRouteSelector(
//                MediaRouteHelper.CATEGORY_CAST, null, null);
        mMediaRouteSelector = MediaRouteHelper.buildMediaRouteSelector(
                MediaRouteHelper.CATEGORY_CAST);
        mMediaRouterCallback = new MediaRouterCallback();

        
    }

    int index = 0;

    public void onPhotoListLoaded(List<Photo> list) {
    	mInfoView.setText("Loaded " + list.size() + " images definitions from Flickr");
        ArrayList imageItems = new ArrayList();


        for(Photo p : list) {
            imageItems.add(new ImageItem(drawableManager.drawableToBitmap(getResources().getDrawable(R.drawable.user_placeholder)), p.getOwnerName(), p.getSquareUrl()));
        }

        gridView = (GridView) findViewById(R.id.gridView);
        customGridAdapter = new GridViewAdapter(this, R.layout.row_grid, imageItems);
        gridView.setAdapter(customGridAdapter);
       
//        gridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
//            public void onItemClick(AdapterView<?> parent, View v,
//                                    int position, long id) {
//                Toast.makeText(HipstaActivity.this, position + "#ItemClick",
//                        Toast.LENGTH_SHORT).show();
//            }
//        });

        index = 0;
        for(Photo p : list) {
            drawableManager.fetchDrawableOnThread(p.getSquareUrl(), gridView, index, new Callback() {

				@Override
				public void execute() {
					mInfoView.setText("Loaded " + ++loaded + " images");
				}
            	
            });
            index++;
        }
        gridView.setAlpha(1.0f);
    }

    private int loaded = 0;


    /**
     * Called when the options menu is first created.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu, menu);
        MenuItem mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item);
        MediaRouteActionProvider mediaRouteActionProvider =
                (MediaRouteActionProvider) MenuItemCompat.getActionProvider(mediaRouteMenuItem);
        mediaRouteActionProvider.setRouteSelector(mMediaRouteSelector);
        
        MenuItem settingsMenuItem = menu.findItem(R.id.action_settings);
        MenuItem refreshMenuItem = menu.findItem(R.id.action_refresh);
        refreshMenuItem.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				if(gridView != null) {
					((GridViewAdapter) gridView.getAdapter()).clear();
					gridView.setAlpha(0.2f);
					new LoadImageListTask(HipstaActivity.this).execute("sarek");
				}
				return false;
			}
		});
    
        return true;
    }

    /**
     * Called on application start. Using the previously selected Cast device, attempts to begin a
     * session using the application name TicTacToe.
     */
    @Override
    protected void onStart() {
        super.onStart();
        mMediaRouter.addCallback(mMediaRouteSelector, mMediaRouterCallback,
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
    }

    /**
     * Removes the activity from memory when the activity is paused.
     */
    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "ENTER - onPause");
        finish();
    }

    /**
     * Attempts to end the current game session when the activity stops.
     */
    @Override
    protected void onStop() {
    	Log.i(TAG, "ENTER - onStop");
        endSession();
        mMediaRouter.removeCallback(mMediaRouterCallback);
        super.onStop();
    }

    /**
     * Ends any existing application session with a Chromecast device.
     */
    private void endSession() {
        if ((mSession != null) && (mSession.hasStarted())) {
            try {
                if (mSession.hasChannel()) {
                    mMessageStream.end();
                }
                mSession.endSession();
            } catch (IOException e) {
                Log.e(TAG, "Failed to end the session.", e);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Unable to end session.", e);
            } finally {
                mSession = null;
            }
        }
    }

    /**
     * Unregisters the media route provider and disposes the CastContext.
     */
    @Override
    public void onDestroy() {
    	Log.i(TAG, "ENTER - onDestroy");
        MediaRouteHelper.unregisterMediaRouteProvider(mCastContext);
        mCastContext.dispose();
        mCastContext = null;
        super.onDestroy();
    }

    /**
     * Returns the screen configuration to portrait mode whenever changed.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
    	Log.i(TAG, "ENTER - onConfigurationChanged");
        super.onConfigurationChanged(newConfig);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }




    private void setSelectedDevice(CastDevice device) {
        mSelectedDevice = device;

        if (mSelectedDevice != null) {
        	mInfoView.setText("Starting session");
            mSession = new ApplicationSession(mCastContext, mSelectedDevice);
            mSession.setListener(mSessionListener);

            try {
                mSession.startSession(APP_NAME);
                mInfoView.setText("Session started!");
            } catch (IOException e) {
                Log.e(TAG, "Failed to open a session", e);
                mInfoView.setText("Failed to open a session");
            }
        } else {
            endSession();
        }
    }

    /**
     * Called when a user selects a route.
     */
    private void onRouteSelected(android.support.v7.media.MediaRouter.RouteInfo route) {
        sLog.d("onRouteSelected: %s", route.getName());
        MediaRouteHelper.requestCastDeviceForRoute(route);
    }

    /**
     * Called when a user unselects a route.
     */
    private void onRouteUnselected(android.support.v7.media.MediaRouter.RouteInfo route) {
        sLog.d("onRouteUnselected: %s", route.getName());
        setSelectedDevice(null);
    }




    /**
     * A class which listens to session start events. On detection, it attaches the message
     * stream.
     */
    private class SessionListener implements ApplicationSession.Listener {
        @Override
        public void onSessionStarted(ApplicationMetadata appMetadata) {
            sLog.d("SessionListener.onStarted");

            ApplicationChannel channel = mSession.getChannel();
            if (channel == null) {
                Log.w(TAG, "onStarted: channel is null");
                return;
            }
            channel.attachMessageStream(mMessageStream);
            mInfoView.setText("Session started");
            // TODO Here maybe call the stream somehow.
        }

        @Override
        public void onSessionStartFailed(SessionError error) {
            sLog.d("SessionListener.onStartFailed: %s", error);
        }

        @Override
        public void onSessionEnded(SessionError error) {
            sLog.d("SessionListener.onEnded: %s", error);
        }
    }

    /**
     * An extension of the GameMessageStream specifically for the TicTacToe game.
     */
    private class CustomHipstaCasterStream extends HipstaCasterMessageStream {


        /**
         * Displays an error dialog.
         */
        @Override
        protected void onError(String errorMessage) {

            new AlertDialog.Builder(HipstaActivity.this)
                    .setTitle("Error")
                    .setMessage(errorMessage)
                    .setCancelable(false)
                    .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    })
                    .create()
                    .show();
        }

        @Override
        protected void start() {
            buildAlertDialog("Title: Start", "Starting...");
        }


        @Override
        protected void end() {
            buildAlertDialog("Title: End", "Ending...");
        }
    }

    private void buildAlertDialog(String title, String msg) {
        new AlertDialog.Builder(HipstaActivity.this)
                .setTitle(title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                })
                .create()
                .show();
    }


    /**
     * An extension of the MediaRoute.Callback specifically for the TicTacToe game.
     */
    private class MediaRouterCallback extends MediaRouter.Callback {
    	
    	
    	
        @Override
		public void onProviderAdded(MediaRouter router, ProviderInfo provider) {
        	Log.i(TAG, "onProviderAdded: " + router);
			super.onProviderAdded(router, provider);
		}

		@Override
		public void onProviderChanged(MediaRouter router, ProviderInfo provider) {
			Log.i(TAG, "onProviderChanged: " + router);
			super.onProviderChanged(router, provider);
		}

		@Override
		public void onProviderRemoved(MediaRouter router, ProviderInfo provider) {
			Log.i(TAG, "onProviderRemoved: " + router);
			super.onProviderRemoved(router, provider);
		}

		@Override
		public void onRouteAdded(MediaRouter router, RouteInfo route) {
			Log.i(TAG, "onRouteAdded: " + route);
			super.onRouteAdded(router, route);
		}

		@Override
		public void onRouteChanged(MediaRouter router, RouteInfo route) {
			Log.i(TAG, "onRouteChanged: " + route);
			super.onRouteChanged(router, route);
		}

		@Override
		public void onRoutePresentationDisplayChanged(MediaRouter router,
				RouteInfo route) {
			Log.i(TAG, "onRoutePresentationDisplayChanged: " + route);
			super.onRoutePresentationDisplayChanged(router, route);
		}

		@Override
		public void onRouteRemoved(MediaRouter router, RouteInfo route) {
			Log.i(TAG, "onRouteRemoved: " + route);
			super.onRouteRemoved(router, route);
		}

		@Override
		public void onRouteVolumeChanged(MediaRouter router, RouteInfo route) {
			Log.i(TAG, "onRouteVolumeChanged: " + route);
			super.onRouteVolumeChanged(router, route);
		}

		@Override
        public void onRouteSelected(MediaRouter router, android.support.v7.media.MediaRouter.RouteInfo route) {
            Log.i(TAG, "onRouteSelected: " + route);
            HipstaActivity.this.onRouteSelected(route);
        }

        @Override
        public void onRouteUnselected(MediaRouter router, android.support.v7.media.MediaRouter.RouteInfo route) {
        	Log.i(TAG, "onRouteUnselected: " + route);
            HipstaActivity.this.onRouteUnselected(route);
        }
    }

    /* MediaRouteAdapter implementation */

    @Override
    public void onDeviceAvailable(CastDevice device, String routeId,
                                  MediaRouteStateChangeListener listener) {
        sLog.d("onDeviceAvailable: %s (route %s)", device, routeId);
        setSelectedDevice(device);
    }

    @Override
    public void onSetVolume(double volume) {
    }

    @Override
    public void onUpdateVolume(double delta) {
    }
}
