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
package com.creationline.common.engine;

import java.lang.reflect.Field;
import java.util.List;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.text.format.Time;

import com.creationline.cloudstack.engine.db.Transactions;
import com.creationline.common.utils.ClLog;

/**
 * RestContentProviderBase is a base from which specific ContentProviders for persisting data
 * related to communicating with a specific API backend can be implemented.
 * 
 * @author thsu
 *
 */
public class RestContentProviderBase extends ContentProvider {
	private static final String TAG = "RestContentProviderBase";
	
	public static final String AUTHORITY = "com.creationline.common.engine.restcontentproviderbase";
	public static final String DATESTAMP = AUTHORITY+".DATESTAMP";
	public static final String TIMESTAMP = AUTHORITY+".TIMESTAMP";
	
	public static final String DB_NAME = "RestTransaction.db";
	protected SQLiteDatabase sqlDb;
	protected SQLiteDatabaseHelper sqlDbHelper;
	private static final int DB_VERSION = 0;
	
	
	public static class SQLiteDatabaseHelper extends SQLiteOpenHelper {
		///This helper class simplifies management of the sql db itself

		public SQLiteDatabaseHelper(Context context) {
			super(context, DB_NAME, null, DB_VERSION);
		}
		
		public SQLiteDatabaseHelper(Context context, String dbName, CursorFactory cursorFactory, int dbVersion) {
			super(context, dbName, cursorFactory, dbVersion);
		}

		@Override
		public void onCreate(SQLiteDatabase db) {
			//sub-classes should implement creation of dbs needed specfic to their case
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			//sub-classes should implement upgrade of dbs specific to their case
		}
		
		public static String makeCreateTableSqlStr(Object columnDefinitionClass) {
			//Build an sql statement that creates a db based off of the name and member vars of the passed in class.
			
			StringBuilder sqlStr = new StringBuilder("CREATE TABLE ");
			sqlStr.append(columnDefinitionClass.getClass().getSimpleName().toLowerCase());
			sqlStr.append(" ( ").append(BaseColumns._ID).append(" INTEGER PRIMARY KEY AUTOINCREMENT, ");  //as an Android SQLite db, the _id column is always assumed to exist
			for(Field field : columnDefinitionClass.getClass().getDeclaredFields()) {
				sqlStr.append(field.getName().toLowerCase());
				sqlStr.append(" TEXT, ");
			}
			sqlStr.deleteCharAt(sqlStr.length()-2);  //remove the last comma
			sqlStr.append(");");
			
			return sqlStr.toString();
		}
		
	}
	
	public String getTableName(Uri uri) {
		List<String> pathSegments = uri.getPathSegments();
		String tableName = pathSegments.get(0);  //table name will always be first segment of path, regardless of whether uri has appended id or not
		return tableName;
	}
	
	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		int numDeleted = 0;
		
		if(uri==null) {
			ClLog.e(TAG, "delete(): uri is null!");
			return -1;
		}
		
		try {
			sqlDb = sqlDbHelper.getWritableDatabase();
			
			selection = transformIdToSelectionClause(uri, selection);
			String tableName = getTableName(uri);
			
			numDeleted = sqlDb.delete(tableName, selection, selectionArgs);
			
			getContext().getContentResolver().notifyChange(uri, null);  //signal observers that something was deleted
		} catch (SQLiteException e) {
			ClLog.e(TAG, "delete(): getWritableDatabase() failed for "+uri);
			ClLog.e(TAG, e);
		} finally {
			closeDbIfNotInUse();
		}
		
		return numDeleted;
	}

	@Override
	public String getType(Uri uri) {
		return null;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		
		if(uri==null) {
			ClLog.e(TAG, "insert(): uri is null!");
			return null;
		}
		
		String tableName = getTableName(uri);
		Uri returnUri = null;
		try {
			sqlDb = sqlDbHelper.getWritableDatabase();

			long rowId = sqlDb.insert(tableName, null, values);  //insert the passed in data as a new row
			
			if(rowId<0){
				ClLog.e(TAG, "Exception inserting values=(" + values + ") into uri=" + uri);
				return null;
			}
			
			//create full uri appended with newly added row id for return
			returnUri = ContentUris.appendId(uri.buildUpon(), rowId).build();  //NOTE: this will return a returnUri with double row ids post-pended if the incoming uri specifies a specific row
																			   //(probably not how this should work (I think sqlDb.insert() will add a new row no
																			   // matter whether uri specifies a row or not), but since we don't need the ability right now,
																			   // leaving as is to save unnecessary coding/processing for the time being)
			getContext().getContentResolver().notifyChange(uri, null);  //signal observers that something was added
		} catch (SQLiteException e) {
			ClLog.e(TAG, "insert(): getWritableDatabase() failed for "+uri);
			ClLog.e(TAG, e);
		} finally {
			closeDbIfNotInUse();
		}
		
		return returnUri;
	}

	@Override
	public boolean onCreate() {
		//sub-classes should implement deletion of dbs specific to their case
		//(defaulting to return false and leaving implementation responsibility to sub-classes)
		return false;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		
		if(uri==null) {
			ClLog.e(TAG, "query(): uri is null!");
			return null;
		}
		
		Cursor c = null;
		try {
			sqlDb = sqlDbHelper.getReadableDatabase();
			
			selection = transformIdToSelectionClause(uri, selection);  //process any trailing id specifiers in the uri
			String tableName = getTableName(uri);
			
			SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
			queryBuilder.setTables(tableName);
			c = queryBuilder.query(sqlDb, projection, selection, selectionArgs, null, null, sortOrder);
			
			c.setNotificationUri(getContext().getContentResolver(), uri);  //register to watch for content uri changes
			c.moveToFirst();  //hack?: for whatever reason, calling moveToFirst() here allows you to close sqlDb w/out affecting the output of the cursor in the calling method (if you don't, the calling method gets a cursor with no data)
		} catch (SQLiteException e) {
			ClLog.e(TAG, "query(): getReadableDatabase() failed for "+uri);
			ClLog.e(TAG, e);
		} finally {
			closeDbIfNotInUse();
		}
		
		return c;
	}

	private static String transformIdToSelectionClause(Uri uri, String selection) {
		//If the content uri contains a trailing id number, this method will
		//create and return a sql WHERE clause that will isolate that record/id.
		//If the uri contains no trailing id number, the WHERE clause is
		//returned unchanged.
		final String ID_EQUALS = Transactions._ID+"=";
		
		try {
			final long specifiedId = ContentUris.parseId(uri);
			if(specifiedId>-1) {
				//narrow query to specific row if id specified in uri
				//(any supplied selection (WHERE) clause will then apply only to that row (which is not of much use :P))
				final String idSelection = ID_EQUALS+specifiedId;
				selection = (selection==null)? idSelection : idSelection+" AND "+selection;
			}
		} catch (NumberFormatException e) {
			//if we got this exception, then uri did not have an id as the last segment so we don't need to create an id selection statement
		}
		return selection;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		int numRowsUpdated = 0;
		
		if(uri==null) {
			ClLog.e(TAG, "update(): uri is null!");
			return -1;
		}
		
		try {
			sqlDb = sqlDbHelper.getWritableDatabase();
			
			selection = transformIdToSelectionClause(uri, selection);  //process any trailing id specifiers in the uri
			String tableName = getTableName(uri);
			
			numRowsUpdated = sqlDb.update(tableName, values, selection, selectionArgs);  //update existing rows using passed in data
			
			getContext().getContentResolver().notifyChange(uri, null);  //signal observers that something was updated
		} catch (SQLiteException e) {
			ClLog.e(TAG, "update(): getWritableDatabase() failed for "+uri);
			ClLog.e(TAG, e);
		} finally {
			closeDbIfNotInUse();
		}
		
		return numRowsUpdated;
	}

	public void closeDbIfNotInUse() {
//		if(sqlDb!=null && !sqlDb.isDbLockedByOtherThreads() && sqlDb.isOpen()) {
//			sqlDb.close();
//		}
	}
	
	public static void deleteAllData(Context context) {
		//sub-classes should implement deletion of dbs specific to their case
	}
	
	public static Bundle getReplyDateTimeFor(Activity activity, final String uriStr) {
		Bundle returnBundle = null;
		final Uri lastestListVmTransaction = Uri.parse(uriStr);
		final String[] columns = new String[] {
			Transactions.REPLY_DATETIME,
		};
		
		Cursor c = activity.getContentResolver().query(lastestListVmTransaction, columns, null, null, Transactions._ID);
		if(c==null || c.getCount()<=0) {
			if(c!=null) { c.close(); };
			ClLog.e("RestContentProviderBase", "query for reply_datetime returned nothing; returning empty bundle");
			return returnBundle;
		}
		
		c.moveToFirst();
		final String lastUpdateTimestampStr = c.getString(c.getColumnIndex(Transactions.REPLY_DATETIME));

		Time readTime = new Time();
		readTime.parse3339(lastUpdateTimestampStr);  //str was saved out using RFC3339 format, so needs to be read in as such
		readTime.switchTimezone("Asia/Tokyo");  //parse3339() automatically converts read in times to UTC.  We need to change it back to the default timezone of the handset (JST in this example)
		String dateStamp = readTime.year+"-"+readTime.month+"-"+readTime.monthDay;
		String timeStamp = readTime.hour+":"+readTime.minute+":"+readTime.second;

		returnBundle = new Bundle();
		returnBundle.putString(RestContentProviderBase.DATESTAMP, dateStamp);
		returnBundle.putString(RestContentProviderBase.TIMESTAMP, timeStamp);

		c.close();

		return returnBundle;
	}

}
