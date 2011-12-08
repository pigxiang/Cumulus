/*******************************************************************************
 * Copyright 2011 Creationline,Inc.
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

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.creationline.cloudstack.R;
import com.creationline.cloudstack.util.Revision;
import com.creationline.cloudstack.util.Version;

public class AboutFragment extends Fragment {
	
	public AboutFragment() {
		//empty constructor is needed by Android for automatically creating fragments from XML declarations
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.aboutfragment, container);
		
		TextView version = (TextView)view.findViewById(R.id.version);
		version.setText(Version.asString());
		
		TextView revision = (TextView)view.findViewById(R.id.revision);
		revision.setText(String.valueOf(Revision.REVISION_NO));
		
		return view;
	}



	
}