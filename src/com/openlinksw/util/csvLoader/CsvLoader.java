/*
 *  Big Database Semantic Metric Tools
 *
 * Copyright (C) 2011 OpenLink Software <bdsmt@openlinksw.com>
 * All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation;  only Version 2 of the License dated
 * June 1991.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.openlinksw.util.csvLoader;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Properties;

import com.openlinksw.bibm.Exceptions.BadSetupException;
import com.openlinksw.bibm.Exceptions.ExceptionException;
import com.openlinksw.bibm.Exceptions.RequestFailedException;
import com.openlinksw.util.DoubleLogger;
import com.openlinksw.util.Options;
import com.openlinksw.util.Options.StringOption;
import com.openlinksw.util.csv2ttl.DBSchema;

/**
 * Loads files generated by TPCH dbgen program into SQL database.
 * 
 * @author ak
 *
 */
public class CsvLoader extends Options {
    DoubleLogger out=DoubleLogger.getOut();
    DoubleLogger err=DoubleLogger.getErr();
    public String ext;
    ArrayDeque<File>sourceFiles=new ArrayDeque<File>();
	String endPoint;
	int timeoutInms=10000;
    DBSchema dbSchema;
    int nClients;
    public Properties connectionProperties=new Properties();

	public CsvLoader(String[] args) throws Exception {
        super("Usage: com.openlinksw.util.csvLoader.CsvLoader [options]... [sourcefiles]...",
        "  sourcefiles can be directories");
        StringOption extOpt=new StringOption("ext <input file extention> (to search in souce directories)",  "csv"
                ,"default: '%%'"+"'"
        );
        StringOption schema=new StringOption("schema <conversion schema> (json file)", null);
        StringOption destDirNameOpt=new StringOption("d <destination directory>", "."
                , "default: current working directory");
        MultiStringOption sqlFiles=new MultiStringOption("loadsql <sql file>"
                , "sql file to exec before loading."
                ," must contail single sql statement."
                , "Option can be used more than once ");
        StringOption driverClassName=new StringOption("dbdriver <DB-Driver Class Name>", "com.mysql.jdbc.Driver"
                ,"default: %%");
        StringOption dburl=new StringOption("dburl <URL of database endpoint>", null);
        IntegerOption nClients=new IntegerOption("mt <integer>", 10
                , "number of client threads"
                ,"default: %%");
        IntegerOption timeout=new IntegerOption("t <integer>", 10000
                , "timeout in milliseconds"
                ,"default: %%");
        StringOption user=new StringOption("user <database user name>", null);
        StringOption password=new StringOption("password <database user's password>", null);

        super.processProgramParameters(args);

        this.endPoint=dburl.getValue();
        this.timeoutInms=timeout.getValue();
        this.nClients=nClients.getValue();

        String schemaName = schema.getValue();
        if (schemaName==null) {
            err.println("No schema provided. Exiting. ");
            usage();            
        }
        try {
            dbSchema=new DBSchema(schemaName);
        } catch (Exception e) {
            err.println("Problems loading schema file "+schemaName);
            err.println(e.getMessage());
            throw e;
        }

        String destDirName=destDirNameOpt.getValue();
        File destDir=new File(destDirName);
        if (!destDir.exists()) {
            destDir.mkdirs();
            if (!destDir.exists()) {
                err.println("Cannot create destination directory: "+destDir.getAbsolutePath());
                usage();                
            }
        }

        this.ext=extOpt.getValue();
		for (String source: super.args) {
				File f=new File(source);
				if (!f.exists()) {
					err.println("file not exists: "+f.getAbsolutePath());
					System.exit(-1);					
				}
				if (f.isDirectory()) {
					String ext1="."+ext;
					String ext2=ext1+".";
					for (String fn: f.list()) {
						if (!(fn.endsWith(ext1)||fn.contains(ext2))) continue;
						File ff=new File(f, fn);
						if (ff.isDirectory()) continue;
						sourceFiles.add(ff);
					}
				} else {
					sourceFiles.add(f);
				}
		}
		if (sourceFiles.size()==0) {
			err.println("No source files. Exiting. ");
			System.exit(-1);			
		}
		
		if (endPoint==null) {
			err.println("No database connection provided. Exiting. ");
			System.exit(-1);			
		}

		try {
			Class.forName(driverClassName.getValue());
		} catch(ClassNotFoundException e) {
			throw new ExceptionException("Driver class not found:", e);
		}
		
		for (String sqlFileName: sqlFiles.getValue()) {
			loadSQL(sqlFileName);
		}
        if (user.getValue()!=null) {
            connectionProperties.setProperty("user", user.getValue());
        }
        if (user.getValue()!=null) {
            connectionProperties.setProperty("password", password.getValue());
        }
	}

    private void usage() {
        printUsageInfos();
        System.exit(-1);
    }

	private void loadSQL(String sqlFileName) {
//        String queryString="exec(file_to_string('"+sqlFileName+"'))";
        String queryString="load '"+sqlFileName+"'";
        out.println("Executing command:"+queryString);
		Connection conn=null;
		try {
			conn = DriverManager.getConnection(endPoint, connectionProperties);
			int timeoutInSeconds=timeoutInms/1000;
			Statement statement = conn.createStatement();
			statement.setQueryTimeout(timeoutInSeconds);
			int result = statement.executeUpdate(queryString);
		} catch (SQLException e0) {
			SQLException e=e0;
			while(e!=null) {
				e.printStackTrace();
				e=e.getNextException();
			}
			throw new ExceptionException("SQLConnection()", e0);
		} finally {
			if (conn!=null) {
				try {
					conn.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		}
        out.println(" ...file "+sqlFileName+" executed");
	}

	synchronized File getNextTask() {
		File f = sourceFiles.poll();
		return f;
	}

	long startTime;
	int maxFailureCount = 10;
	ArrayList<Failure> failures = new ArrayList<Failure>();

	synchronized boolean reportFailure(String comment, Exception e) {
		failures.add(new Failure(comment, e));
		
		return failures.size()>=maxFailureCount;
	}
	
	void checkFailures() {
		if (failures.size()==0) {
	        out.println("No failures reported.");
		} else {
	        err.println("Some file convertions failed:");
	        for (int k=0; k<failures.size(); k++) {
	            Failure failure = failures.get(k);
                err.println(failure.comment);
                Exception exception = failure.exception;
                err.println(exception.getMessage());
                exception.printStackTrace();
	        }
		}
	}

	void runAll() {
		int availableProcessors = Runtime.getRuntime().availableProcessors();
		int numOfFiles = sourceFiles.size();
		out.println("numOfFiles="+numOfFiles);
        int nThreads=Math.min(availableProcessors, nClients);
        nThreads=Math.min(nThreads, numOfFiles);
		Thread[] threads=new Thread[nThreads];
		startTime=System.currentTimeMillis();
		for (int k=0; k<nThreads; k++) {
			Thread thread = new Thread(new Worker (this));
			threads[k]=thread;
			thread.start();
		}
		try {
			for (int k=0; k<nThreads; k++) {
				threads[k].join();
			}
		} catch (InterruptedException e) {
			for (int k=0; k<nThreads; k++) {
				threads[k].interrupt();
			}
		}
	}

	private void testDriverShutdown() {
		// TODO Auto-generated method stub
		
	}

	public static void main(String[] args) throws Exception {
		CsvLoader csv2ttl =null;
		try {
			csv2ttl = new CsvLoader(args);
			csv2ttl.runAll();
		} catch (ExceptionException e) {
			DoubleLogger.getErr().println(e.getMessages());
		} catch (BadSetupException e) {
			DoubleLogger.getErr().println(e.getMessage());
        } catch (RequestFailedException e) {
            if (e.getMessage()!=null) {
                DoubleLogger.getErr().println("Request failed: ", e.getMessage());              
            }
        } catch (Throwable e) {
            e.printStackTrace();
		} finally {
			if (csv2ttl==null) return;
            csv2ttl.checkFailures();
            long interval=System.currentTimeMillis()-csv2ttl.startTime;
            long sec=interval/1000;
            long min=sec/60;  sec-=min*60;
            long hr=min/60;  min-=hr*60;
            csv2ttl.out.printf("Loading time=%02d:%02d:%02d", hr, min, sec);
			csv2ttl.testDriverShutdown();
		}
	}

}
