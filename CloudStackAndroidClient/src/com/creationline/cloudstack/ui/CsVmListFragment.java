/*******************************************************************************
 * Copyright 2011-2012 Creationline,Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.creationline.cloudstack.ui;

import net.londatiga.android.ActionItem;
import net.londatiga.android.QuickAction;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.ResourceCursorAdapter;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.creationline.cloudstack.CloudStackAndroidClient;
import com.creationline.cloudstack.R;
import com.creationline.cloudstack.engine.CsApiConstants;
import com.creationline.cloudstack.engine.CsRestContentProvider;
import com.creationline.cloudstack.engine.CsRestService;
import com.creationline.cloudstack.engine.db.Transactions;
import com.creationline.cloudstack.engine.db.Vms;
import com.creationline.cloudstack.utils.QuickActionUtils;
import com.creationline.common.engine.RestServiceBase;
import com.creationline.common.utils.ClLog;

public class CsVmListFragment extends CsListFragmentBase implements LoaderManager.LoaderCallbacks<Cursor> {
	public static class INTENT_ACTION {
		public static final String CALLBACK_LISTVMS = "com.creationline.cloudstack.ui.CsVmListFragment.CALLBACK_LISTVMS";
		public static final String CALLBACK_STARTSTOPREBOOTVM = "com.creationline.cloudstack.ui.CsVmListFragment.CALLBACK_STARTSTOPREBOOTVM";
	}

	private static final int CSVM_LIST_LOADER = 0x01;
    private ResourceCursorAdapter adapter = null;  //backer for this list
    private BroadcastReceiver listVmsCallbackReceiver = null;  //used to receive request success/failure notifs from CsRestService
    private BroadcastReceiver startStopRebootVmCallbackReceiver = null;  //used to receive request success/failure notifs from CsRestService
    private static Bundle vmsWithInProgressRequests = new Bundle();  //used to keep track of which VMs have requests in-progress
    private boolean isProvisioned = false;  //whether currently have api/secret key or not (determined at onCreate())
    
    //actionid constants for use with quickaction menus
    private static final int START_VM = 0;
    private static final int STOP_VM = 1;
    private static final int REBOOT_VM = 2;
    
    //constants used as keys for saving/restoring app state on pause/resume
    private static final String CSVMLIST_DATESTAMP = "com.creationline.cloudstack.ui.CsVmListFragment.CSVMLIST_DATESTAMP";
    private static final String CSVMLIST_TIMESTAMP = "com.creationline.cloudstack.ui.CsVmListFragment.CSVMLIST_TIMESTAMP";
    

    public class CsVmListAdapter extends ResourceCursorAdapter {
    	//This adaptor use strictly for use with the CsVmListFragment class/layout, and expects specific data to fill its contents.
    	
    	public CsVmListAdapter(Context context, int layout, Cursor c, int flags) {
			super(context, layout, c, flags);
		}

		@Override
    	public void bindView(View view, Context context, Cursor cursor) {
			setTextViewWithString(view, R.id.id, cursor, Vms.ID);
			setTextViewWithString(view, R.id.displayname, cursor, Vms.DISPLAYNAME);
			setTextViewWithString(view, R.id.name, cursor, Vms.NAME);
			setTextViewWithString(view, R.id.state, cursor, Vms.STATE);
			setTextViewWithString(view, R.id.serviceofferingname, cursor, Vms.SERVICEOFFERINGNAME);
			setTextViewWithString(view, R.id.templatedisplaytext, cursor, Vms.TEMPLATEDISPLAYTEXT);
			setTextViewWithString(view, R.id.hypervisor, cursor, Vms.HYPERVISOR);
			setTextViewWithString(view, R.id.cpunumber, cursor, Vms.CPUNUMBER);
			setTextViewWithString(view, R.id.cpuspeed, cursor, Vms.CPUSPEED);
			setTextViewWithString(view, R.id.memory, cursor, Vms.MEMORY);

			configureAttributesBasedOnState(view);
    	}

		/**
		 * Looks for a TextView with textViewId in view and sets its text value to the String value from cursor under columnName.
		 * @param view view that contains TextView to update
		 * @param textViewId id of TextView to update
		 * @param cursor cursor with String data to use as updated text
		 * @param columnName name of column in cursor that contains the String data to use as updated text
		 */
		public void setTextViewWithString(View view, int textViewId, Cursor cursor, String columnName) {
			TextView tv = (TextView) view.findViewById(textViewId);
			tv.setText(cursor.getString(cursor.getColumnIndex(columnName)));
		}
		
		/**
		 * Configures the color/quickaction of the TextView/ImageView specified by stateTextViewId/quickactionIconId
		 * based on the value of the state of the VM.
		 * Currently, these states are hard-coded to "running" and "stopped", with a catch all for unrecognized values.
		 * 
		 * @param view View containing TextView/ImageView specified by stateTextViewId/quickactionIconId
		 */
		public void configureAttributesBasedOnState(View view) {
			//clear out the animations for the icon/progressCircle each time,
			//otherwise, the set animation will run automatically each time the
			//widget visibility is changed
			ImageView quickActionIcon = (ImageView)view.findViewById(R.id.quickactionicon);
			ProgressBar quickActionProgress = (ProgressBar)view.findViewById(R.id.quickactionprogress);
			quickActionIcon.clearAnimation();
			quickActionProgress.clearAnimation();
			
			TextView vmidText = (TextView)view.findViewById(R.id.id);
			TextView stateText = (TextView)view.findViewById(R.id.state);
			final String vmid = vmidText.getText().toString();
			final String state = stateText.getText().toString();
			
			//NOTE: The way this inprogress processing (and subsequent quickaction button animation) is done currently
			//      is hacky and is not usable cross-activity.  It would be cleaner to implement more like how
			//      CsSnapshotListFragment handles its inprogress/animation processing.
			final boolean stateUpdated = determineIfStateHasBeenUpdatedByServer(vmid, state);

			//for the vm state text, we change its color depending on the current state of the vm
			if(Vms.STATE_VALUES.RUNNING.equalsIgnoreCase(state)) {
				stateText.setTextColor(getResources().getColorStateList(R.color.vmrunning_color_selector));
				QuickActionUtils.assignQuickActionTo(view, quickActionIcon, createRunningStateQuickAction(view));
				onVmStateUpdate(view, stateText, stateUpdated);
				QuickActionUtils.showQuickActionIcon(quickActionIcon, quickActionProgress, stateUpdated);
				
			} else if (Vms.STATE_VALUES.STOPPED.equalsIgnoreCase(state)) {
				stateText.setTextColor(getResources().getColorStateList(R.color.vmstopped_color_selector));
				QuickActionUtils.assignQuickActionTo(view, quickActionIcon, createStoppedStateQuickAction(view));
				onVmStateUpdate(view, stateText, stateUpdated);
				QuickActionUtils.showQuickActionIcon(quickActionIcon, quickActionProgress, stateUpdated);
				
			} else if (Vms.STATE_VALUES.STARTING.equalsIgnoreCase(state)) {
				stateText.setTextColor(getResources().getColorStateList(R.color.vmstarting_color_selector));
				QuickActionUtils.showQuickActionProgress(quickActionIcon, quickActionProgress, stateUpdated);
				
			} else if (Vms.STATE_VALUES.STOPPING.equalsIgnoreCase(state)) {
				stateText.setTextColor(getResources().getColorStateList(R.color.vmstopping_color_selector));
				QuickActionUtils.showQuickActionProgress(quickActionIcon, quickActionProgress, stateUpdated);
				
			}  else if (Vms.STATE_VALUES.REBOOTING.equalsIgnoreCase(state)) {
				stateText.setTextColor(getResources().getColorStateList(R.color.vmrebooting_color_selector));
				QuickActionUtils.showQuickActionProgress(quickActionIcon, quickActionProgress, stateUpdated);
				
			} else {
				//if we run into an unknown state, give...
				stateText.setTextColor(getResources().getColorStateList(R.color.vmunknown_color_selector));  //...state a default color
				QuickActionUtils.showNeitherQuickAction(quickActionIcon, quickActionProgress, stateUpdated); //...and no quickaction nor progresscircle
			}
			
		}

		public boolean determineIfStateHasBeenUpdatedByServer(final String vmid, final String currentState) {
			final String inProgressState = vmsWithInProgressRequests.getString(vmid);
			final boolean requestIsPending = inProgressState!=null;
			final boolean isNotInProgressState = currentState.equalsIgnoreCase(Vms.STATE_VALUES.RUNNING) || currentState.equalsIgnoreCase(Vms.STATE_VALUES.STOPPED);
			final boolean stateHasBeenUpdated = !currentState.equalsIgnoreCase(inProgressState);
			if(stateHasBeenUpdated) { vmsWithInProgressRequests.remove(vmid); };
			final boolean stateUpdated =  requestIsPending && isNotInProgressState && stateHasBeenUpdated;
			
			return stateUpdated;
		}

		public void onVmStateUpdate(View view, TextView stateText, final boolean stateUpdated) {
			if(stateUpdated) {
				stateText.startAnimation(QuickActionUtils.getFadein_decelerate());
				
				TextView displayNameText = (TextView)view.findViewById(R.id.displayname);
				Toast.makeText(getActivity(), "\""+displayNameText.getText().toString()+"\" is now "+stateText.getText().toString().toLowerCase(), Toast.LENGTH_SHORT).show();
			}
		}

		/**
		 * Sets the supplied quickAction as the onClick handler for the ImageView specified by
		 * quickactionIconId in the view.
		 * 
		 * @param view View containing ImageView specified by quickActionIconId
		 * @param quickActionIcon id of ImageView to use as the icon/"button" trigger for this quickaction menu
		 * @param quickAction quickaction to assign to the onClick handler
		 */
		public void assignQuickActionTo(View view, ImageView quickActionIcon, final QuickAction quickAction) {
			quickActionIcon.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					quickAction.show(v);
				}
			});
		}

		@Override
		public void notifyDataSetChanged() {
			//update the current #-of-vm count
			TextView footervmnum = (TextView)getListView().findViewById(R.id.footervmnum);
			if(footervmnum!=null) {
				final int count = getCursor().getCount();
				footervmnum.setText(String.valueOf(count));
			}
			
			final String[] columns = new String[] { Vms.ID };
			final String whereClause = Vms.STATE+"=?";
			
			//update the current #-of-running-vm count
			TextView footerrunningvmnum = (TextView)getListView().findViewById(R.id.footerrunningvmnum);
			if(footerrunningvmnum!=null) {
				final String[] selectionArgs = new String[] { Vms.STATE_VALUES.RUNNING };
				Cursor runningVms = getActivity().getContentResolver().query(Vms.META_DATA.CONTENT_URI, columns, whereClause, selectionArgs, null);
				final int runningVmCount = runningVms.getCount();
				footerrunningvmnum.setText(String.valueOf(runningVmCount));
				runningVms.close();
			}

			//update the current #-of-stopped-vm count
			TextView footerstoppedvmnum = (TextView)getListView().findViewById(R.id.footerstoppedvmnum);
			if(footerstoppedvmnum!=null) {
				final String[] selectionArgs = new String[] { Vms.STATE_VALUES.STOPPED };
				Cursor stoppedVms = getActivity().getContentResolver().query(Vms.META_DATA.CONTENT_URI, columns, whereClause, selectionArgs, null);
				final int stoppedVmCount = stoppedVms.getCount();
				footerstoppedvmnum.setText(String.valueOf(stoppedVmCount));
				stoppedVms.close();
			}
			
			//double-check whether we are still provisioned (use could have reset account in the mean time) and update button state if necessary
	        isProvisioned = isProvisioned();
			if(isProvisioned==false) {
				View csvmlistcommandfooter = getActivity().findViewById(R.id.csvmlistcommandfooter);
				setRefreshButtonEnabled(csvmlistcommandfooter, false);
			}

			super.notifyDataSetChanged();
		}
		
    }


    public CsVmListFragment() {
    	//empty constructor is needed by Android for automatically creating fragments from XML declarations
    }
    
    @Override
	public void onCreate(Bundle savedInstanceState) {
    	super.onCreate(savedInstanceState);
    	
    	isProvisioned = isProvisioned();  //this needs to be done first as the isProvisioned member var is used at various places
    	registerListVmsCallbackReceiver();
    	registerStartStopRebootVmCallbackReceiver();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		adapter = setupListAdapter(CSVM_LIST_LOADER);
	}

	/** Called when the activity is first created. */
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        //add summary footer to the list
        addAndInitFooter(savedInstanceState, R.layout.csvmlistsummaryfooter, R.id.csvmlistsummaryfooterviewswitcher);
        
        //add command footer to the list
        View csvmlistcommandfooter = addAndInitFooter(savedInstanceState, R.layout.csvmlistcommandfooter, R.id.csvmlistcommandfooterviewswitcher);
        
        setRefreshButtonClickHandler(csvmlistcommandfooter);
		if(isProvisioned) {
			setRefreshButtonEnabled(csvmlistcommandfooter, true);
        } else {
        	setRefreshButtonEnabled(csvmlistcommandfooter, false);
        }

		adapter = setupListAdapter(CSVM_LIST_LOADER);
        
		final boolean isFreshAppStart = savedInstanceState==null;  //a "fresh app start" means the app was started fresh by the user, not as a result of orientation changes or such
		if(isFreshAppStart) {
			if(isProvisioned) {
				//do an initial refresh for data since it may have been a while since we were running
				makeListVmCall();
			}
		} else {
			//if we have any saved time/date stamps of the last refresh, use them 
			final String savedDatestamp = savedInstanceState.getString(CSVMLIST_DATESTAMP);
			final String savedTimestamp = savedInstanceState.getString(CSVMLIST_TIMESTAMP);

			if(savedDatestamp!=null) {
				setTextView(csvmlistcommandfooter, R.id.lastrefresheddatestamp, savedDatestamp);
			}
			if(savedTimestamp!=null) {
				setTextView(csvmlistcommandfooter, R.id.lastrefreshedtimestamp, savedTimestamp);
			}
		}
        
    }


	@Override
	public void onResume() {
//    	registerVmListCallbackReceiver();
		
		super.onResume();
	}
	
	public ResourceCursorAdapter setupListAdapter(final int listLoaderId) {
		//if we have previous, existing adapter (say, from orientation change), just use it instead of creating new one to prevent mem leak
		ResourceCursorAdapter listAdapter = (ResourceCursorAdapter)getListAdapter();
		if(listAdapter==null) {
			//set-up the loader & adapter for populating this list
			getLoaderManager().initLoader(listLoaderId, null, this);
			listAdapter = new CsVmListAdapter(getActivity().getApplicationContext(), R.layout.csvmlistitem, null, CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER);
		}
		//we want to null-and-set the listAdapter explicitly regardless of whether we are
		//creating from scratch or re-setting an existing adapter b/c if we don't do this,
		//the list seems to lose track of the display of the footers (the list seems to
		//recognize the footers as existing, the footer ids exist in the layout, but are
		//not shown on screen after the list view is reloaded after being swiped too
		//far off-screen (current guess is that being swiped off the multi-list screen
		//results in a state that is not quite paused (destroys ui-level elements),
		//but not quite destroyed either (adapter, non-ui part of footer, layout)))
		setListAdapter(null);
		setListAdapter(listAdapter);
		return listAdapter;
	}

	public void registerListVmsCallbackReceiver() {
		listVmsCallbackReceiver = new BroadcastReceiver(){
        	//This handles callback intents broadcasted by CsRestService
        	@Override
        	public void onReceive(Context contenxt, Intent intent) {
        		FragmentActivity activity = getActivity();
        		if(activity==null) {
        			return;
        		}
        		
        		View csvmlistcommandfooter = getActivity().findViewById(R.id.csvmlistcommandfooter);
        		if(csvmlistcommandfooter==null) {
        			return;
        		}
        		setRefreshButtonEnabled(csvmlistcommandfooter, true);
        		setProgressCircleVisible(csvmlistcommandfooter, ProgressBar.INVISIBLE);

        		Bundle bundle = intent.getExtras();
        		final int successOrFailure = bundle.getInt(CsRestService.CALL_STATUS);
        		final String updatedUriStr = bundle.getString(RestServiceBase.PAYLOAD_FIELDS.UPDATED_URI);
        		if(updatedUriStr==null || successOrFailure==CsRestService.CALL_STATUS_VALUES.CALL_FAILURE) {
        			return;
        		}

        		final Bundle parsedDateTime =  CsRestContentProvider.getReplyDateTimeFor(activity, updatedUriStr);
        		if(parsedDateTime!=null) {
        			setTextView(csvmlistcommandfooter, R.id.lastrefresheddatestamp, parsedDateTime.getString(CsRestContentProvider.DATESTAMP));
        			setTextView(csvmlistcommandfooter, R.id.lastrefreshedtimestamp, parsedDateTime.getString(CsRestContentProvider.TIMESTAMP));
        		}
        	}
        };
        getActivity().registerReceiver(listVmsCallbackReceiver, new IntentFilter(CsVmListFragment.INTENT_ACTION.CALLBACK_LISTVMS));  //activity will now GET intents broadcast by CsRestService (filtered by CALLBACK_LISTVMS action)
	}

	public void registerStartStopRebootVmCallbackReceiver() {
		startStopRebootVmCallbackReceiver = new BroadcastReceiver(){
			//This handles callback intents broadcasted by CsRestService
			@Override
			public void onReceive(Context contenxt, Intent intent) {
				Bundle bundle = intent.getExtras();
				final int successOrFailure = bundle.getInt(CsRestService.CALL_STATUS);
				final String vmId = bundle.getString(Vms.ID);

				if(successOrFailure==CsRestService.CALL_STATUS_VALUES.CALL_FAILURE) {
					//the request failed, so revert the state of vm back to what it was based on call, and refresh
					final String inProgressState = vmsWithInProgressRequests.getString(vmId);
					if(Vms.STATE_VALUES.STARTING.equals(inProgressState)) {
						updateVmStateOnDb(vmId, Vms.STATE_VALUES.STOPPED);
					} else if(Vms.STATE_VALUES.STOPPING.equals(inProgressState) || Vms.STATE_VALUES.REBOOTING.equals(inProgressState)) {
						updateVmStateOnDb(vmId, Vms.STATE_VALUES.RUNNING);
					} else {
						ClLog.e("CsVmListFragment.registerStartStopRebootVmCallbackReceiver():onReceive():", "got unknown inProgressState="+inProgressState);
					}
					adapter.notifyDataSetChanged();  //faking a data set change so the list will refresh itself

				} else {
					;  //do nothing on success as the memory-state/db-state comparison code will know when an operation has succeeded and show the appropriate toast
				}
			}
		};
		getActivity().registerReceiver(startStopRebootVmCallbackReceiver, new IntentFilter(CsVmListFragment.INTENT_ACTION.CALLBACK_STARTSTOPREBOOTVM));  //activity will now GET intents broadcast by CsRestService (filtered by CALLBACK_STARTSTOPREBOOTVM action)
	}
	
	@Override
	public void onPause() {
		super.onPause();
	}

	@Override
	public void onDestroy() {
		unregisterCallbackReceiver(listVmsCallbackReceiver);
		unregisterCallbackReceiver(startStopRebootVmCallbackReceiver);
		
		releaseListAdapter();

		super.onDestroy();
	}

	public void releaseListAdapter() {
		//zero-out list adapter-related references so gc can work
		getLoaderManager().destroyLoader(CSVM_LIST_LOADER);
		setListAdapter(null);
		adapter = null;
	}
	
	@Override
	public void onSaveInstanceState(Bundle outState) {
		TextView lastrefresheddatestamp = (TextView)getActivity().findViewById(R.id.lastrefresheddatestamp);
		TextView lastrefreshedtimestamp = (TextView)getActivity().findViewById(R.id.lastrefreshedtimestamp);
		
		outState.putString(CSVMLIST_DATESTAMP, lastrefresheddatestamp.getText().toString());
		outState.putString(CSVMLIST_TIMESTAMP, lastrefreshedtimestamp.getText().toString());
		
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id) {
		final TextView vmIdText = (TextView)v.findViewById(R.id.id);
		
		//start details view activity with id of selected vm
		Intent intent = new Intent();
		intent.setClass(getActivity(), CsVmDetailsFragmentActivity.class);
		intent.putExtra(Vms.class.toString()+Vms.ID, vmIdText.getText().toString());
		startActivity(intent);
	}

	public QuickAction createRunningStateQuickAction(final View view) {
        final ActionItem stopVmItem = new ActionItem(STOP_VM, "Stop VM", getResources().getDrawable(R.drawable.button_stop));
        final ActionItem rebootVmItem = new ActionItem(REBOOT_VM, "Reboot VM", getResources().getDrawable(R.drawable.button_synchronize));
		
		//create QuickAction. Use QuickAction.VERTICAL or QuickAction.HORIZONTAL param to define layout orientation
		QuickAction runningStateQuickAction = new QuickAction(getActivity(), QuickAction.HORIZONTAL);
		
		//add action items into QuickAction
		runningStateQuickAction.addActionItem(stopVmItem);
		runningStateQuickAction.addActionItem(rebootVmItem);
		
		//Set listener for action item clicked
		runningStateQuickAction.setOnActionItemClickListener(new QuickAction.OnActionItemClickListener() {          
			@Override
			public void onItemClick(QuickAction source, int pos, int actionId) {
				switch(actionId) {
					case STOP_VM:
						makeStartOrStopOrRebootVmCall(view, CsApiConstants.API.stopVirtualMachine);
						break;
					case REBOOT_VM:
						makeStartOrStopOrRebootVmCall(view, CsApiConstants.API.rebootVirtualMachine);
						break;
					default:
						ClLog.e("runningStateQuickAction", "Unrecognized actionId="+actionId);
				}
			}
		});
		runningStateQuickAction.setAnimStyle(QuickAction.ANIM_GROW_FROM_RIGHT);

		return runningStateQuickAction;
	}

	public QuickAction createStoppedStateQuickAction(final View view) {
		final ActionItem startVmItem = new ActionItem(START_VM, "Start VM", getResources().getDrawable(R.drawable.button_play));
		
		//create QuickAction. Use QuickAction.VERTICAL or QuickAction.HORIZONTAL param to define layout orientation
		QuickAction stoppedStateQuickAction = new QuickAction(getActivity(), QuickAction.HORIZONTAL);
		
		//add action items into QuickAction
		stoppedStateQuickAction.addActionItem(startVmItem);
		
		//Set listener for action item clicked
		stoppedStateQuickAction.setOnActionItemClickListener(new QuickAction.OnActionItemClickListener() {          
			@Override
			public void onItemClick(QuickAction source, int pos, int actionId) {
				makeStartOrStopOrRebootVmCall(view, CsApiConstants.API.startVirtualMachine);
			}
		});
		stoppedStateQuickAction.setAnimStyle(QuickAction.ANIM_GROW_FROM_RIGHT);
		
		return stoppedStateQuickAction;
	}
    
	public void makeStartOrStopOrRebootVmCall(View itemView, final String commandName) {
		TextView idText = (TextView)itemView.findViewById(R.id.id);
		final String vmid = idText.getText().toString();

		ImageView quickActionIcon = (ImageView)itemView.findViewById(R.id.quickactionicon);
		ProgressBar quickActionProgress = (ProgressBar)itemView.findViewById(R.id.quickactionprogress);
		QuickActionUtils.showQuickActionProgress(quickActionIcon, quickActionProgress, true);

		String inProgressState = null;
		if(CsApiConstants.API.startVirtualMachine.equalsIgnoreCase(commandName)) {
			inProgressState = Vms.STATE_VALUES.STARTING;
		} else if(CsApiConstants.API.stopVirtualMachine.equalsIgnoreCase(commandName)) {
			inProgressState = Vms.STATE_VALUES.STOPPING;
		} else if(CsApiConstants.API.rebootVirtualMachine.equalsIgnoreCase(commandName)) {
			inProgressState = Vms.STATE_VALUES.REBOOTING;
		}
		
		updateVmStateOnDb(vmid, inProgressState);  //update vm data with in-progress state
		vmsWithInProgressRequests.putString(vmid, inProgressState);  //cache in-progress state, so we compare and know when it has been updated by the server reply
		
        //make the rest call to cs server to start/stop/reboot vm represented by itemView
        final String action = CsRestService.TEST_CALL;   
        Bundle apiCmd = new Bundle();
        apiCmd.putString(CsRestService.COMMAND, commandName);
        apiCmd.putString(Vms.ID, vmid);
        apiCmd.putString(Transactions.CALLBACK_INTENT_FILTER, CsVmListFragment.INTENT_ACTION.CALLBACK_STARTSTOPREBOOTVM);
        Intent csRestServiceIntent = CsRestService.createCsRestServiceIntent(getActivity(), action, apiCmd);
        getActivity().startService(csRestServiceIntent);
	}

	public void updateVmStateOnDb(final String vmid, String state) {
		ContentValues cv = new ContentValues();
		cv.put(Vms.STATE, state);
		final String whereClause = Vms.ID+"=?";
		final String[] selectionArgs = new String[] { vmid };
		getActivity().getContentResolver().update(Vms.META_DATA.CONTENT_URI, cv, whereClause, selectionArgs);
	}
	
	public void setRefreshButtonClickHandler(final View view) {
		Button refreshbutton = (Button)view.findViewById(R.id.refreshbutton);
		refreshbutton.setOnClickListener(new OnClickListener() {
		    @Override
		    public void onClick(View v) {
		    	makeListVmCall();
		    }
		  });
	}
	
	public void makeListVmCall() {
		SharedPreferences preferences = getActivity().getSharedPreferences(CloudStackAndroidClient.SHARED_PREFERENCES.PREFERENCES_NAME, Context.MODE_PRIVATE);
		final String username = preferences.getString(CloudStackAndroidClient.SHARED_PREFERENCES.USERNAME_SETTING, null);

		View csvmlistcommandfooter = getActivity().findViewById(R.id.csvmlistcommandfooter);
		setRefreshButtonEnabled(csvmlistcommandfooter, false);
		setProgressCircleVisible(csvmlistcommandfooter, ProgressBar.VISIBLE);
		
		if(username!=null) {
			//make the rest call to cs server for vm data
			final String action = CsRestService.TEST_CALL;   
			Bundle apiCmd = new Bundle();
			apiCmd.putString(CsRestService.COMMAND, CsApiConstants.API.listVirtualMachines);
			apiCmd.putString(Vms.ACCOUNT, username);
	        apiCmd.putString(Transactions.CALLBACK_INTENT_FILTER, CsVmListFragment.INTENT_ACTION.CALLBACK_LISTVMS);
			Intent csRestServiceIntent = CsRestService.createCsRestServiceIntent(getActivity(), action, apiCmd);  //user api
			getActivity().startService(csRestServiceIntent);
		}
	}
	

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String[] columns = new String[] {
        		Vms._ID,
        		Vms.ID,
        		Vms.DISPLAYNAME,
        		Vms.NAME,
        		Vms.STATE,
        		Vms.SERVICEOFFERINGNAME,
        		Vms.TEMPLATEDISPLAYTEXT,
        		Vms.HYPERVISOR,
        		Vms.CPUNUMBER,
        		Vms.CPUSPEED,
        		Vms.MEMORY
        };
        CursorLoader cl = new CursorLoader(getActivity(), Vms.META_DATA.CONTENT_URI, columns, null, null, null);
		return cl;
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
		adapter.swapCursor(cursor);
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		adapter.swapCursor(null);
	}
    
}
