package edu.columbia.cs.psl.ioclones.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.mapper.MapperWrapper;

import edu.columbia.cs.psl.ioclones.driver.IODriver;
import edu.columbia.cs.psl.ioclones.driver.SimAnalysisDriver;
import edu.columbia.cs.psl.ioclones.driver.SimAnalysisDriver.IOSim;
import edu.columbia.cs.psl.ioclones.pojo.ClassInfo;
import edu.columbia.cs.psl.ioclones.pojo.IORecord;
import edu.columbia.cs.psl.ioclones.pojo.MethodInfo;
import edu.columbia.cs.psl.ioclones.xmlconverter.BlackConverter;
import edu.columbia.cs.psl.ioclones.xmlconverter.EnumMapConverter;

public class IOUtils {
	
	private static Logger logger = LogManager.getLogger(IOUtils.class);
	
	private static final Set<Class> blackObjects = new HashSet<Class>();
	
	private static Object boLock = new Object();
	
	private static Set<String> blackFields = new HashSet<String>();
	
	private static Object bfLock = new Object();
	
	private static XStream xstream = null;
	
	private static Gson gson = null;
	
	private static Object streamLock = new Object();
	
	private static Connection connection = null;
	
	private static final String csvHeader = "method_1, method_id1, method_2, method_id2, in_sim, out_sim, total_sim \n";
	
	public static XStream getXStream() {
		synchronized(streamLock) {
			if (xstream == null) {
				xstream = new XStream() {
					@Override
					protected MapperWrapper wrapMapper(MapperWrapper next) {
						return new MapperWrapper(next) {
							@Override
							public boolean shouldSerializeMember(Class definedIn, 
									String fieldName) {
								if (definedIn == Object.class) {
									return false;
								}
								
								String fieldKey = ClassInfoUtils.genClassFieldKey(definedIn.getName(), fieldName);
								synchronized(bfLock) {
									if (blackFields.contains(fieldKey)) {
										return false;
									}
								}
								
								/*if (fieldName.equals("this$0")) {
									synchronized(bfLock) {
										System.out.println("Dont serialize: " + fieldKey);
										blackFields.add(fieldKey);
									}
									return false;
								}
								return super.shouldSerializeMember(definedIn, fieldName);*/
								
								Field f = null;
								LinkedList<Class> queue = new LinkedList<Class>();
								queue.add(definedIn);
								while (queue.size() > 0) {
									Class clazz = queue.removeFirst();
									try {
										f = clazz.getDeclaredField(fieldName);
									} catch (NoSuchFieldException ex) {									
										if (clazz.getSuperclass() != null) {
											queue.add(clazz.getSuperclass());
										}
									}
								}
								
								if (f == null) {
									//Not in super classes;
									logger.info(fieldName + " in interfaces");
									for (Class inter: definedIn.getInterfaces()) {
										queue.add(inter);
									}
								}
								
								while (queue.size() > 0) {
									Class curInter = queue.removeFirst();
									try {
										f = curInter.getDeclaredField(fieldName);
									} catch (NoSuchFieldException ex) {
										for (Class superInter: curInter.getInterfaces()) {
											queue.add(superInter);
										}
									}
								}
								
								if (f == null) {
									logger.error("Cannot find field: " + fieldKey);
									return super.shouldSerializeMember(definedIn, fieldName);
								} else {
									if (f.isSynthetic()) {
										logger.info("Synth. field: " + fieldKey);
										synchronized(bfLock) {
											blackFields.add(fieldKey);
										}
										return false;
									} else if (BlackConverter.shouldBlock(f.getType())) {
										//Don't serialize reader, writer, scanner, stringtokenizer
										return false;
									} else {
										return super.shouldSerializeMember(definedIn, fieldName);
									}
								}
							}
						};
					}
				};
				//xstream.setMode(XStream.NO_REFERENCES);
				xstream.ignoreUnknownElements();
				BlackConverter bc = new BlackConverter();
				//System.out.println("BC object: " + bc);
				xstream.registerConverter(bc, XStream.PRIORITY_VERY_HIGH);
				EnumMapConverter mec = new EnumMapConverter();
				//System.out.println("MEC object: " + mec);
				xstream.registerConverter(mec, XStream.PRIORITY_VERY_HIGH);
				//InnerClassConverter ic = new InnerClassConverter(xstream);
				//xstream.registerConverter(ic, XStream.PRIORITY_VERY_HIGH);
			}
			
			return xstream;
		}
	}
	
	public static Gson getGson() {
		if (gson == null) {
			GsonBuilder gb = new GsonBuilder();
			gb.setPrettyPrinting();
			gson = gb.enableComplexMapKeySerialization().create();
		}
		return gson;
	}
	
	public static Connection getConnection(String db, String userName, String pw) {
		if (connection == null) {
			try {
				Class.forName("com.mysql.jdbc.Driver");
				connection = DriverManager.getConnection(db, userName, pw);
			} catch (Exception ex) {
				logger.error("Error: ", ex);
				logger.error("Reporting database and username: " + db + " " + userName);
			}
		}
		
		try {
			int counter = 0;
			while (counter < 3) {
				boolean isValid = connection.isValid(10);
				if (isValid) {
					return connection;
				} else {
					logger.warn("#" + counter++ + " connection fails");
					connection = DriverManager.getConnection(db, userName, pw);
				}
			}
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
		
		return null;
	}
	
	public static String getExtension(String fileName) {
		int lastDot = fileName.lastIndexOf(".");
		if (lastDot == -1) {
			return null;
		}
		
		String extension = fileName.substring(lastDot + 1, fileName.length());
		return extension;
	}
		
	public static <T> void writeJson(T obj, TypeToken typeToken, String fileName) {
		String toWrite = objToJson(obj, typeToken);
		
		try {
			File f = new File(fileName);
			if (!f.exists()) {
				f.createNewFile();
			}
			
			BufferedWriter bw = new BufferedWriter(new FileWriter(f));
			bw.write(toWrite);
			bw.close();
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
	}
	
	public static <T> T readJson(File f, TypeToken typeToken) {
		GsonBuilder gb = new GsonBuilder();
		gb.setPrettyPrinting();
		Gson gson = gb.create();
		
		try {
			 JsonReader jr = new JsonReader(new FileReader(f));
			 T ret = gson.fromJson(jr, typeToken.getType());
			 jr.close();
			 return ret;
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
		return null;
	}
	
	public static <T> String objToJson(Object obj, TypeToken<T> typeToken) {
		String toWrite = getGson().toJson(obj, typeToken.getType());
		return toWrite;
	}
	
	public static <T> T jsonToObj(String json, TypeToken<T> typeToken) {
		T obj = getGson().fromJson(json, typeToken.getType());
		return obj;
	}
		
	public static Set<String> blackPrefix() {
		Set<String> ret = new HashSet<String>();
		
		try {
			ClassLoader clazzLoader = IOUtils.class.getClassLoader();
			InputStream blackStream = clazzLoader.getResourceAsStream("blacklist.txt");
			if (blackStream == null) {
				logger.warn("Find no black list file");
				return ret;
			}
			
			InputStreamReader isr = new InputStreamReader(blackStream);
			BufferedReader br = new BufferedReader(isr);
			String buf = "";
			while ((buf = br.readLine()) != null) {
				ret.add(buf);
			}
			isr.close();
			br.close();
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
		
		return ret;
	}
		
	public static Object newObject(Object obj) {
		try {
			XStream xstream = getXStream();
			String objString = xstream.toXML(obj);
			//logger.info("obj: " + obj);
			//logger.info("objString: " + objString);
			/*if (obj.getClass().isArray()) {
				System.out.println(Array.getLength(obj));
			}*/
			Object newObj = xstream.fromXML(objString);
			return newObj;
		} catch (Exception ex) {
			logger.error("Fail to create new obj: ", obj.getClass().getName());
			logger.error("Trace", ex);
			XStream xstream = new XStream();
			BlackConverter bc = new BlackConverter();
			xstream.registerConverter(bc, XStream.PRIORITY_VERY_HIGH);
			logger.error("Contents: " + xstream.toXML(obj));
			System.exit(-1);
		}
		
		return null;
	}
	
	public static String fromObj2XML(Object obj) {
		try {
			XStream xstream = getXStream();
			String objString = xstream.toXML(obj);
			return objString;
		} catch (Exception ex) {
			logger.error("Fail to convert obj to xml: " + obj.getClass());
			logger.error("Trace", ex);
		}
		
		return null;
	}
	
	public static Object fromXML2Obj(File f) {
		try {
			XStream xstream = getXStream();
			Object obj = xstream.fromXML(f);
			return obj;
		} catch (Exception ex) {
			logger.error("Fail to convert file to obj: " + f.getAbsolutePath());
			logger.error("Trace", ex);
		}
		
		return null;
	}
	
	public static Object fromXML2Obj(String xmlString) {
		try {
			XStream xstream = getXStream();
			Object obj = xstream.fromXML(xmlString);
			return obj;
		} catch (Exception ex) {
			logger.error("Fail to convert xml string to obj: " + xmlString);
			logger.error("Trace", ex);
		}
		return null;
	}
	
	public static void writeFile(String contents, File f) {
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(f));
			bw.write(contents);
			bw.close();
		} catch (Exception ex) {
			logger.error("Fail to write file: ", ex);
		}
	}
	
	public static void cleanNonSerializables(Collection c) {
		//Need a better way to filter objs, now follow xstream's documents
		Iterator it = c.iterator();
		while (it.hasNext()) {
			Object o = it.next();
			
			if (o == null) {
				continue ;
			}
			
			if (shouldRemove(o)) {
				it.remove();
				logger.info("Remove obj: " + o);
			}
		}
	}
	
	public static boolean shouldRemove(Object o) {
		if (o == null) {
			return true;
		}
		
		Class objClass = o.getClass();
		if (OutputStream.class.isAssignableFrom(objClass) 
				|| InputStream.class.isAssignableFrom(objClass) 
				|| ClassLoader.class.isAssignableFrom(objClass) 
				|| Socket.class.isAssignableFrom(objClass)) {
			return true;
		}
		
		if (o.getClass().isArray()) {
			int length = Array.getLength(o);
			
			//Assume all objects in array are the same
			Object first = null;
			int counter = 0;
			while (counter < length) {
				first = Array.get(o, counter++);
				if (first != null) {
					break ;
				}
			}
			return shouldRemove(first);
		} else if (Collection.class.isAssignableFrom(objClass)) {
			Collection tmp = (Collection)o;
			
			Iterator tmpIt = tmp.iterator();
			Object first = null;
			while (tmpIt.hasNext()) {
				first = tmpIt.next();
				if (first != null) {
					break ;
				}
			}
			return shouldRemove(first);
		} else if (Map.class.isAssignableFrom(objClass)) {
			Map map = (Map)o;
			
			Iterator<Entry> tmpIt = map.entrySet().iterator();
			Object firstKey = null;
			Object firstVal = null;
			boolean[] get = {false, false};
			while (tmpIt.hasNext()) {
				Entry e = tmpIt.next();
				if (!get[0]) {
					if (e.getKey() != null) {
						get[0] = true;
						firstKey = e.getKey();
					}
				}
				
				if (!get[1]) {
					if (e.getValue() != null) {
						get[1] = true;
						firstVal = e.getValue();
					}
				}
				
				if (get[0] && get[1]) {
					break ;
				}
			}
			
			return shouldRemove(firstKey) || shouldRemove(firstVal);
		} else {
			return false;
		}
	}
	
	private static boolean attemptSerialization(Object obj) {
		synchronized(boLock) {
			try {
				System.out.println("Check class: " + obj.getClass());
				String fileName = "ser/" + obj.getClass() + ".xml";
				File toWrite = new File(fileName);
				BufferedWriter bw = new BufferedWriter(new FileWriter(toWrite));
				String xmlString = fromObj2XML(obj);
				bw.write(xmlString);
				bw.close();
				
				File toRead = new File(fileName);
				Object deser = fromXML2Obj(toRead);
				if (deser == null) {
					//toWrite.delete();
					System.out.println(obj.getClass() + " fails");
					return false;
				} else {
					//toWrite.delete();
					System.out.println(obj.getClass() + " passes");
					System.out.println(obj);
					return true;
				}
			} catch (Exception ex) {
				logger.error("Error: ", ex);
			}
			
			return false;
		}
	}
	
	public static void unzipClassInfo() {
		File infoDir = new File(IODriver.profileDir);
		if (!infoDir.exists()) {
			logger.warn("Class info directory not exist");
			return ;
		}
		
		for (File f: infoDir.listFiles()) {
			if (f.getName().endsWith(".zip")) {
				unzipClassInfo(f);
			}
		}
		logger.info("Total loaded class profiles: " + GlobalInfoRecorder.getClassInfo().size());
	}
	
	public static void unzipClassInfo(File zipFile) {
		try {
			ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
			ZipEntry curEntry = null;
			
			while ((curEntry = zis.getNextEntry()) != null) {
				//System.out.println("Cur entry: " + curEntry.getName());
				
				if (curEntry.isDirectory()) {
					continue ;
				}
				
				String extension = getExtension(curEntry.getName());
				if (extension.equals("xml")) {
					StringBuilder sb = new StringBuilder();
					byte[] buffer = new byte[1024];
					int read = 0;
					
					while ((read = zis.read(buffer, 0, 1024)) >= 0) {
						sb.append(new String(buffer, 0, read));
					}
					
					ClassInfo classInfo = (ClassInfo) fromXML2Obj(sb.toString());
					GlobalInfoRecorder.registerClassInfo(classInfo);
				}
			}
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
	}
	
	public static void unzipIORecords(File zipFile, List<IORecord> records) {
		try {
			ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
			ZipEntry curEntry = null;
			
			while ((curEntry = zis.getNextEntry()) != null) {
				//System.out.println("Cur entry: " + curEntry.getName());
				
				if (curEntry.isDirectory()) {
					continue ;
				}
				
				String entryName = curEntry.getName();
				if (entryName.contains("<init>") || entryName.contains("<clinit>")) {
					continue ;
				}
				
				String extension = getExtension(curEntry.getName());
				if (extension.equals("xml")) {
					StringBuilder sb = new StringBuilder();
					byte[] buffer = new byte[1024];
					int read = 0;
					
					while ((read = zis.read(buffer, 0, 1024)) >= 0) {
						sb.append(new String(buffer, 0, read));
					}
					
					IORecord io = (IORecord) fromXML2Obj(sb.toString());
					
					if (io.getMethodKey() == null) {
						logger.error("Null method key: " + curEntry.getName());
					} else {
						records.add(io);
					}
				}
			}
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
	}
	
	public static void collectIOZips(File iofile, List<File> zips) {
		//System.out.println("io file: " + iofile.getAbsolutePath());
		if (iofile.isDirectory()) {
			for (File f: iofile.listFiles()) {
				collectIOZips(f, zips);
			}
		} else {
			if (iofile.getName().startsWith(".")) {
				return ;
			}
			
			String extension = IOUtils.getExtension(iofile.getName());
			if (extension.equals("zip")) {
				zips.add(iofile);
			}
			/*if (extension.equals("xml")) {
				files.add((IORecord)fromXML2Obj(iofile));
			} else if (extension.equals("zip")) {
				zips.add(iofile);
			}*/
		}
	}
	
	public static void collectIORecords(File dir, List<IORecord> recorder) {
		if (!dir.exists()) {
			logger.error("Non-exisiting directory: " + dir.getAbsolutePath());
			return ;
		}
		
		for (File f: dir.listFiles()) {
			if (f.getName().startsWith(".")) {
				continue ;
			}
			
			if (f.isDirectory()) {
				collectIORecords(f, recorder);
			} else {
				if (!f.getName().endsWith("xml")) {
					logger.error("Invalid profile: " + f.getAbsolutePath());
				} else {
					IORecord record = (IORecord)fromXML2Obj(f);
					if (record != null) {
						recorder.add(record);
					}
				}
			}
		}
	}
	
	public static void exportIOSimilarity(Collection<IOSim> simObjs, 
			String db, 
			String userName, 
			String pw, 
			String codebase, 
			int total_ios,
			int comparisons,
			long execTime) {
		File resultDir = new File("results");
		if (!resultDir.exists()) {
			resultDir.mkdir();
		}
		
		codebase = codebase.replace("/", "");
		String fileName = "results/" + codebase + "_" + (new Date()).getTime() + ".csv";
		File resultFile = new File(fileName);
		if (!resultFile.exists()) {
			try {
				resultFile.createNewFile();
			} catch (Exception ex) {
				logger.error("Error, ", ex);
				resultFile = new File((new Date()).getTime() + ".csv");
				
				try {
					resultFile.createNewFile();
				} catch (Exception ex2) {
					logger.error("Error, ", ex2);
				}
			}
		}
		
		StringBuilder sb = new StringBuilder();
		sb.append(csvHeader);
		
		String totalQuery = "INSERT INTO hitoshio_summary (codebase, total_ios, comparisons, exec_time, timestamp) VALUES(?, ?, ?, ?, ?)";
		PreparedStatement totalStmt = null;
		
		String query = "INSERT INTO hitoshio_row (comp_id, method1, m_id1, method2, m_id2, inSim, outSim, sim) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
		PreparedStatement stmt = null;
		
		if (db != null && userName != null && pw != null) {
			try {
				Connection conn = getConnection(db, userName, pw);
				
				if (conn != null) {
					totalStmt = conn.prepareStatement(totalQuery, Statement.RETURN_GENERATED_KEYS);
					stmt = conn.prepareStatement(query);
				} else {
					logger.error("Fail to get connection...");
				}
				
			} catch (Exception ex) {
				logger.error("Error: ", ex);
			}
		}
		
		int compKey = -1;
		if (totalStmt != null) {
			try {
				Date now = new Date();
				Timestamp ts = new Timestamp(now.getTime());
				
				totalStmt.setString(1, codebase);
				totalStmt.setInt(2, total_ios);
				totalStmt.setInt(3, comparisons);
				totalStmt.setLong(4, execTime);
				totalStmt.setTimestamp(5, ts);
				
				totalStmt.execute();
				ResultSet rs = totalStmt.getGeneratedKeys();
				if(!rs.next())
					throw new IllegalStateException("Unable to insert comparison data");
				compKey = rs.getInt(1);
				logger.info("Comp key: " + compKey);
			} catch (Exception ex) {
				logger.error("Error: ", ex);
			}
		}
		
		int counter = 0;		
		for (IOSim simObj: simObjs) {
			Entry<String, Integer> firstEntry = simObj.methods.firstEntry();
			Entry<String, Integer> secondEntry = simObj.methods.lastEntry();
			sb.append(firstEntry.getKey() + ",");
			sb.append(firstEntry.getValue() + ",");
			sb.append(secondEntry.getKey() + ",");
			sb.append(secondEntry.getValue() + ",");
			sb.append(simObj.inSim + ",");
			sb.append(simObj.outSim + ",");
			sb.append(simObj.sim + "\n");
			
			if (stmt != null) {
				try {
					stmt.setInt(1, compKey);
					String firstKey = firstEntry.getKey();
					if (firstKey.length() >= 250) {
						logger.warn("Long first key: " + firstKey);
						firstKey = firstKey.substring(0, 250);
						logger.warn("Truncate to: " + firstKey);
					}
					stmt.setString(2, firstKey);
					stmt.setInt(3, firstEntry.getValue());
					String secondKey = secondEntry.getKey();
					if (secondKey.length() >= 250) {
						logger.warn("Long second key: " + secondKey);
						secondKey = secondKey.substring(0, 250);
						logger.warn("Truncate to: " + secondKey);
					}
					stmt.setString(4, secondKey);
					stmt.setInt(5, secondEntry.getValue());
					stmt.setDouble(6, simObj.inSim);
					stmt.setDouble(7, simObj.outSim);
					stmt.setDouble(8, simObj.sim);
					
					stmt.addBatch();
				} catch (Exception ex) {
					logger.error("Error: ", ex);
				}
			}
			
			counter++;
			if (counter > 5000) {
				try {
					Files.write(resultFile.toPath(), sb.toString().getBytes(), StandardOpenOption.APPEND);
					sb = new StringBuilder();
					counter = 0;
					
					if (stmt != null) {
						stmt.executeBatch();
						stmt.clearBatch();
					}
				} catch (Exception ex) {
					logger.error("Error: ", ex);
				}
			}
		}
		
		if (counter > 0) {
			try {
				Files.write(resultFile.toPath(), sb.toString().getBytes(), StandardOpenOption.APPEND);
				if (stmt != null) {
					stmt.executeBatch();
				}
			} catch (Exception ex) {
				logger.error("Error: ", ex);
			}
		}
		
		logger.info("Exporting ends: " + codebase);
	}
	
	public static void loadMethodIODeps(String dbName) {
		try {
			Class.forName("org.sqlite.JDBC");
			String dbpath = "jdbc:sqlite:" + IODriver.profileDir + "/" + dbName + ".db";
			Connection conn = DriverManager.getConnection(dbpath);
			logger.info("Connect to sqlite: " + dbName);
			
			String loadClass = "SELECT * FROM CLASSINFO";
			Statement loadClassStmt = conn.createStatement();
			ResultSet classResults = loadClassStmt.executeQuery(loadClass);
			TypeToken<List<String>> listToken = new TypeToken<List<String>>(){};
			TypeToken<Map<Integer, TreeSet<Integer>>> mapToken = 
					new TypeToken<Map<Integer, TreeSet<Integer>>>(){};
			int loadedCounter = 0;
			Map<Integer, ClassInfo> classMap = new HashMap<Integer, ClassInfo>();
			while (classResults.next()) {
				loadedCounter++;
				if (loadedCounter % 5000 == 0) {
					logger.info("Loaded class: " + loadedCounter);
				}
				int cid = classResults.getInt("ID");
				String className = classResults.getString("CLASSNAME");
				String parent = classResults.getString("PARENT");
				List<String> interfaces = IOUtils.jsonToObj(classResults.getString("INTERFACES"), listToken);
				List<String> children = IOUtils.jsonToObj(classResults.getString("CHILDREN"), listToken);
				
				ClassInfo classInfo = new ClassInfo(className);
				classInfo.setParent(parent);
				classInfo.setInterfaces(interfaces);
				classInfo.setChildren(children);
				
				classMap.put(cid, classInfo);
				GlobalInfoRecorder.registerClassInfo(classInfo);
			}
			
			String loadMethod = "SELECT * FROM METHODINFO";
			Statement loadMethodStmt = conn.createStatement();
			ResultSet methodResult = loadMethodStmt.executeQuery(loadMethod);
			
			int methodCounter = 0;
			while (methodResult.next()) {
				methodCounter++;
				if (methodCounter % 10000 == 0) {
					logger.info("Loaded methods: " + methodCounter);
				}
				
				int cid = methodResult.getInt("C_ID");
				String methodArgs = methodResult.getString("METHOD_DESC");
				int level = methodResult.getInt("LEVEL");
				boolean isFinal = methodResult.getBoolean("FINAL");
				boolean leaf = methodResult.getBoolean("LEAF");
				Map<Integer, TreeSet<Integer>> writtenParams = 
						IOUtils.jsonToObj(methodResult.getString("WRITTEN_PARAMS"), mapToken);
				
				MethodInfo mi = new MethodInfo();
				mi.setLevel(level);
				mi.setFinal(isFinal);
				mi.leaf = leaf;
				mi.setWrittenParams(writtenParams);
				
				classMap.get(cid).addMethodInfo(methodArgs, mi);
			}
			
			conn.close();
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
	}
	
	public static void exportJVMIODeps(Collection<ClassInfo> classes) {
		exportMethodIODeps(classes, "methodeps");
	}
	
	public static void exportMethodIODeps(Collection<ClassInfo> classes, String dbName) {
		File classInfoDir = new File(IODriver.profileDir);
		if (!classInfoDir.exists()) {
			classInfoDir.mkdirs();
		}
		
		try {
			Class.forName("org.sqlite.JDBC");
			String dbpath = "jdbc:sqlite:" + IODriver.profileDir + "/" + dbName + ".db";
			Connection conn = DriverManager.getConnection(dbpath);
			conn.setAutoCommit(false);
			logger.info("Connect to sqlite");
			
			Statement dropStmt = conn.createStatement();
			String dropProbe = "DROP TABLE IF EXISTS CLASSINFO";
			dropStmt.executeUpdate(dropProbe);
			
			Statement methodDropStmt = conn.createStatement();
			String dropMethod = "DROP TABLE IF EXISTS METHODINFO";
			methodDropStmt.executeUpdate(dropMethod);
			
			
			Statement createClassStmt = conn.createStatement();
			String createClass = "CREATE TABLE CLASSINFO "
					+ "(ID INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ "CLASSNAME TEXT NOT NULL, "
					+ "PARENT TEXT, "
					+ "INTERFACES TEXT, "
					+ "CHILDREN TEXT)"; 
			createClassStmt.executeUpdate(createClass);
			logger.info("Create class table");
			
			Statement createMethodStmt = conn.createStatement();
			String createMethod = "CREATE TABLE METHODINFO "
					+ "(C_ID INTEGER NOT NULL, "
					+ "METHOD_DESC TEXT NOT NULL, "
					+ "LEVEL INTEGER NOT NULL, "
					+ "FINAL BOOLEAN NOT NULL, "
					+ "LEAF BOOLEAN NOT NULL, "
					+ "WRITTEN_PARAMS TEXT)";
			createMethodStmt.executeUpdate(createMethod);
			logger.info("Create method table");
			
			int classCounter = 0;
			for (ClassInfo c: classes) {
				classCounter++;
				if (classCounter % 1000 == 0) {
					logger.info("Store classes: " + classCounter);
				}
				
				String insertClass = "INSERT INTO CLASSINFO (CLASSNAME, PARENT, INTERFACES, CHILDREN) "
						+ "VALUES(?, ?, ?, ?)";
				PreparedStatement classStmt = conn.prepareStatement(insertClass, Statement.RETURN_GENERATED_KEYS);
				classStmt.setString(1, c.getClassName());
				classStmt.setString(2, c.getParent());
				
				TypeToken<List<String>> interToken = new TypeToken<List<String>>(){}; 
				classStmt.setString(3, IOUtils.objToJson(c.getInterfaces(), interToken));
				classStmt.setString(4, IOUtils.objToJson(c.getChildren(), interToken));
				classStmt.executeUpdate();
				
				ResultSet classInsert = classStmt.getGeneratedKeys();;
				int classIdx = classInsert.getInt(1);
				//logger.info("Class idx: " + c.getClassName() + " " + classIdx);
				
				String insertMethod = "INSERT INTO METHODINFO (C_ID, METHOD_DESC, LEVEL, FINAL, LEAF, WRITTEN_PARAMS) "
						+ "VALUES(?, ?, ?, ?, ?, ?)";
				PreparedStatement methodStatement = conn.prepareStatement(insertMethod);
				TypeToken<Map<Integer, TreeSet<Integer>>> writtenType = 
						new TypeToken<Map<Integer, TreeSet<Integer>>>(){};
				for (String nameDesc: c.getMethodInfo().keySet()) {
					MethodInfo methodInfo = c.getMethodInfo(nameDesc);
					methodStatement.setInt(1, classIdx);
					methodStatement.setString(2, nameDesc);
					methodStatement.setInt(3, methodInfo.getLevel());
					methodStatement.setBoolean(4, methodInfo.isFinal());
					methodStatement.setBoolean(5, methodInfo.leaf);
					
					Map<Integer, TreeSet<Integer>> writtens = methodInfo.getWrittenParams();
					methodStatement.setString(6, IOUtils.objToJson(writtens, writtenType));
					
					methodStatement.addBatch();
				}
				methodStatement.executeBatch();
			}
			conn.commit();
			conn.close();
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
	}
	
	public static void main(String[] args) {
		//loadMethodIODeps("methodeps");
		//System.out.println("Loaded class info: " + GlobalInfoRecorder.getClassInfo().size());
		Object o = null;
		System.out.println(newObject(o) == null);
	}
	
	/*public static void main(String[] args) throws Exception {
		Console c = System.console();
		String filePath = c.readLine("CSV file path: ");
		File csvFile = new File(filePath);
		if (!csvFile.exists()) {
			System.err.println("CSV file does not exist");
			System.exit(-1);
		}
		System.out.println("Confirm file path: " + csvFile.getAbsolutePath());
		
		char[] pwArray = c.readPassword("Password: ");
		String pw = new String(pwArray);
		String db = SimAnalysisDriver.urlHeader + "liberty.cs.columbia.edu:3306/hitoshio" + SimAnalysisDriver.urlTail;
		String userName = "root";
		Connection connect = getConnection(db, userName, pw);
		
		if (connect == null) {
			System.out.println("Fail to connect to database");
			System.exit(-1);
		}	
		
		String compString = c.readLine("Comp key: ");
		int compKey = Integer.valueOf(compString);
		System.out.println("Confirm compKey: " + compKey);
		
		String query = "INSERT INTO hitoshio_row (comp_id, method1, m_id1, method2, m_id2, inSim, outSim, sim) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
		PreparedStatement stmt = connect.prepareStatement(query);
		try {
			BufferedReader br = new BufferedReader(new FileReader(csvFile));
			String buf = "";
			
			//Header
			br.readLine();
			
			while ((buf = br.readLine()) != null) {
				System.out.println("Data: " + buf);
				String[] data = buf.split(",");
				stmt.setInt(1, compKey);
				stmt.setString(2, data[0]);
				stmt.setInt(3, Integer.valueOf(data[1]));
				stmt.setString(4, data[2]);
				stmt.setInt(5, Integer.valueOf(data[3]));
				stmt.setDouble(6, Double.valueOf(data[4]));
				stmt.setDouble(7, Double.valueOf(data[5]));
				stmt.setDouble(8, Double.valueOf(data[6]));
				
				stmt.addBatch();
			}
			
			stmt.executeBatch();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}*/
}
