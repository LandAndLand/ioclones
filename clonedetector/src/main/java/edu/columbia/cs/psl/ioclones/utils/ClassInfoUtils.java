package edu.columbia.cs.psl.ioclones.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.internal.MethodSorter;
import org.objectweb.asm.Type;

import edu.columbia.cs.psl.ioclones.analysis.DependentValue;
import edu.columbia.cs.psl.ioclones.analysis.SymbolicValueAnalyzer;
import edu.columbia.cs.psl.ioclones.pojo.CalleeRecord;
import edu.columbia.cs.psl.ioclones.pojo.ClassInfo;
import edu.columbia.cs.psl.ioclones.pojo.MethodInfo;

public class ClassInfoUtils {
	
	private static final Logger logger = LogManager.getLogger(ClassInfoUtils.class);
	
	public static final String RE_SLASH = ".";
	
	public static final String DELIM = "-";
	
	private static final Set<String> BLACK_PREFIX = IOUtils.blackPrefix();
	
	private static final File libDir = new File(System.getProperty("user.home") + "/.m2");
	
	private static final Set<Type> immutables = new HashSet<Type>();
	
	private static final HashMap<String, TreeSet<Integer>> paramCache = 
			new HashMap<String, TreeSet<Integer>>();
	
	static {
		Type stringType = Type.getType(String.class);
		immutables.add(stringType);
		Type intType = Type.getType(Integer.class);
		immutables.add(intType);
		Type doubleType = Type.getType(Double.class);
		immutables.add(doubleType);
		Type floatType = Type.getType(Float.class);
		immutables.add(floatType);
		Type longType = Type.getType(Long.class);
		immutables.add(longType);
		Type shortType = Type.getType(Short.class);
		immutables.add(shortType);
		Type byteType = Type.getType(Byte.class);
		immutables.add(byteType);
		Type booleanType = Type.getType(Boolean.class);
		immutables.add(booleanType);
		Type charType = Type.getType(Character.class);
		immutables.add(charType);
	}
	
	public static String genClassFieldKey(String className, String fieldName) {
		return className + DELIM + fieldName;
	}
	
	public static String cleanType(String typeString) {
		//return typeString.replace("/", ClassUtils.RE_SLASH).replace(";", "");
		return typeString.replace("/", ClassInfoUtils.RE_SLASH);
	}
	
	public static String[] genMethodKey(String owner, String name, String desc) {
		Type[] args = Type.getArgumentTypes(desc);
		Type returnType = Type.getReturnType(desc);
		StringBuilder argBuilder = new StringBuilder();
		for (Type arg: args) {
			String argString = null;
			if (arg.getSort() == Type.ARRAY) {
				int dim = arg.getDimensions();
				Type arrType = arg.getElementType();
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < dim; i++) {
					sb.append("[");
				}
				sb.append(parseType(arrType));
				argString = sb.toString();
			} else {
				argString = parseType(arg);
			}
			argBuilder.append(cleanType(argString) + "+");
		}
		
		String argString = null;
		if (argBuilder.length() == 0) {
			argString = "()";
		} else {
			argString = "(" + argBuilder.substring(0, argBuilder.length() - 1) + ")";
		}
		
		//methodKey = className + methodName + args
		String methodKey = owner + DELIM + name + DELIM + argString;
		String returnString = returnType.toString();
		if (returnType.getSort() == Type.OBJECT) {
			returnString = returnString.substring(1, returnString.length());
		}
		String typeString = cleanType(returnString);
		String[] ret = {methodKey, typeString};
		return ret;
	}
	
	public static String parsePkgName(String className) {
		int lastDot = className.lastIndexOf(".");
		String pkgName = className.substring(0, lastDot);
		return pkgName;
	}
	
	public static String parseType(Type t) {
		if (t.getSort() == Type.OBJECT) {
			return t.getInternalName();
		} else {
			return t.toString();
		}
	}
	
	public static boolean checkAccess(int access, int mask) {
		return ((access & mask) != 0);
	}
	
	public static boolean shouldInstrument(String className) {
		for (String b: BLACK_PREFIX) {
			if (className.startsWith(b)) {
				return false;
			}
		}
		
		return true;
	}
	
	public static boolean checkProtectionDomain(String domainPath) {
		File domainFile = new File(domainPath);
		try {
			String checkDir = domainFile.getParentFile().getCanonicalPath();
			String libDirPath = libDir.getCanonicalPath();
			//System.out.println("Check dir: " + checkDir);
			//System.out.println("Lib path: " + libDirPath);
			if (libDirPath.equals(checkDir)) {
				return false;
			} else {
				return true;
			}
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
		
		return false;
	}
	
	public static boolean checkOwnerInParams(Set<Integer> params, DependentValue dv) {
		DependentValue ptr = dv;
		while (ptr != null) {
			if (params.contains(dv.id)) {
				return true;
			}
			
			ptr = dv.owner;
		}
		return false;
	}
	
	public static void collectClassesInJar(File jarFile, 
			List<InputStream> container) {
		try {
			JarFile jarInstance = new JarFile(jarFile);
			Enumeration<JarEntry> entries = jarInstance.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String entryName = entry.getName();
				//logger.info("Entry: " + entryName);
				
				if (entry.isDirectory()) {
					continue ;
				}
				
				if (!entryName.startsWith("java/")) {
					continue ;
				}
				
				if (entryName.endsWith(".class")) {
					InputStream entryStream = jarInstance.getInputStream(entry);
					container.add(entryStream);
					
					//logger.info("Retrieve class: " + entry.getName());
					/*String className = entryName.replace("/", ".");
					className = className.substring(0, className.lastIndexOf("."));
					Class clazz = loader.loadClass(className);
					System.out.println("Class name: " + clazz.getProtectionDomain().toString());*/
				}
			}
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
	}
	
	public static void genJVMLookupTable(File jarFile, Map<String, InputStream> lookup) {
		try {
			JarFile jarInstance = new JarFile(jarFile);
			Enumeration<JarEntry> entries = jarInstance.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String entryName = entry.getName();
				
				if (entry.isDirectory()) {
					continue ;
				}
				
				if (entryName.endsWith(".class")) {
					//String className = entryName.replace("/", ".");
					String className = entryName.substring(0, entryName.lastIndexOf("."));
					className = className.replace("/", ".");
					InputStream entryStream = jarInstance.getInputStream(entry);
					lookup.put(className, entryStream);
					
					//logger.info("Retrieve class: " + entry.getName());
					/*
					Class clazz = loader.loadClass(className);
					System.out.println("Class name: " + clazz.getProtectionDomain().toString());*/
				}
			}
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
	}
	
	public static void genRepoClasses(File file, List<InputStream> container) {
		if (file.getName().startsWith(".")) {
			return ;
		}
		
		if (file.isDirectory()) {
			for (File f: file.listFiles()) {
				genRepoClasses(f, container);
			}
		} else {
			if (file.getName().endsWith(".class")) {
				try {
					InputStream is = new FileInputStream(file);
					container.add(is);
				} catch (Exception ex) {
					logger.error("Error: ", ex);
				}
			}
		}
	}
	
	public static boolean isImmutable(Type t) {
		return immutables.contains(t);
	}
	
	public static void stabelizeMethod(MethodInfo method) {
		method.stabelized = true;
		if (method.writtenInputs == null) {
			method.writtenInputs = new TreeSet<Integer>();
		}
		
		for (CalleeRecord ci: method.getCallees()) {
			String calleeKey = ci.getMethodKey();
			System.out.println("Callee key: " + calleeKey);
			String className = calleeKey.split(ClassInfoUtils.DELIM)[0];
			
			//From callee to caller map
			Map<Integer, Integer> potentialOutputs = ci.getPotentialOutputs();
			
			//Conservatively query the class hierarchy, level here does not matter
			TreeSet<Integer> writtenParams = null;
			if (ci.fixed) {
				writtenParams = queryMethod(className, calleeKey, true, MethodInfo.PUBLIC);
			} else {
				writtenParams = queryMethod(className, calleeKey, false, MethodInfo.PUBLIC);
			}
			System.out.println("writtenParams: " + writtenParams);
			
			for (Integer calleeId: potentialOutputs.keySet()) {
				if (writtenParams.contains(calleeId)) {
					int callerId = potentialOutputs.get(calleeId);
					method.writtenInputs.add(callerId);
				}
			}
		}
		
		//SymbolicValueAnalyzer.analyzeValue(method);
	}
	
	public static TreeSet<Integer> queryMethod(String className, 
			String methodKey, 
			boolean isFixed, 
			int level) {
		if (paramCache.containsKey(methodKey)) {
			return paramCache.get(methodKey);
		}
		
		TreeSet<Integer> writtenParams = new TreeSet<Integer>();
		ClassInfo ci = GlobalInfoRecorder.queryClassInfo(className);
		if (ci == null) {
			logger.warn("Missed class: " + className);
			return writtenParams;
		}
		
		if (isFixed) {
			MethodInfo mi = ci.getMethods().get(methodKey);
			if (mi != null) {
				if (!mi.stabelized) {
					stabelizeMethod(mi);
				}
				
				if (mi.writtenInputs.size() > 0) {
					writtenParams.addAll(mi.writtenInputs);
				}
			} else {
				TreeSet<Integer> superQuery = queryMethod(ci.getParent(), methodKey, isFixed, level);
				writtenParams.addAll(superQuery);
			}
			paramCache.put(methodKey, writtenParams);
			
			return writtenParams;
		}
		
		MethodInfo mi = ci.getMethods().get(methodKey);
		if (mi != null) {
			if (!mi.stabelized) {
				stabelizeMethod(mi);
			}
			
			writtenParams.addAll(mi.writtenInputs);
			level = mi.getLevel();
		}
		
		//Climb up
		if (ci.getParent() != null) {
			TreeSet<Integer> superQuery = searchUp(ci.getParent(), methodKey, level);
			writtenParams.addAll(superQuery);
		}
		
		//Go down
		for (String child: ci.getChildren()) {
			TreeSet<Integer> childQuery = searchDown(child, methodKey, level);
			writtenParams.addAll(childQuery);
		}
		paramCache.put(methodKey, writtenParams);
		
		return writtenParams;
	}
	
	public static TreeSet<Integer> searchUp(String className, 
			String methodKey, 
			int level) {	
		ClassInfo ci = GlobalInfoRecorder.queryClassInfo(className);
		MethodInfo mi = ci.getMethods().get(methodKey);
		
		TreeSet<Integer> ret = new TreeSet<Integer>();
		if (mi != null && mi.getLevel() <= level && !mi.isFinal()) {
			if (!mi.stabelized) {
				stabelizeMethod(mi);
			}
			
			if (mi.writtenInputs != null && mi.writtenInputs.size() > 0) {
				ret.addAll(mi.writtenInputs);
			}
		}
		
		if (ci.getParent() != null) {
			TreeSet<Integer> superQuery = null;
			if (mi == null) {
				superQuery = searchUp(ci.getParent(), methodKey, level);
			} else {
				superQuery = searchUp(ci.getParent(), methodKey, mi.getLevel());
			}
			ret.addAll(superQuery);
		}
		
		return ret;
	}
	
	public static TreeSet<Integer> searchDown(String className, 
			String methodKey, 
			int level) {
		ClassInfo ci = GlobalInfoRecorder.queryClassInfo(className);
		MethodInfo mi = ci.getMethods().get(methodKey);
		TreeSet<Integer> ret = new TreeSet<Integer>();
		if (mi != null && mi.getLevel() >= level) {
			if (!mi.stabelized) {
				stabelizeMethod(mi);
			}
			
			if (mi.writtenInputs != null && mi.writtenInputs.size() > 0) {
				ret.addAll(mi.writtenInputs);
			}
		}
		
		if (mi != null) {
			if (!mi.isFinal()) {
				ci.getChildren().forEach(child->{
					TreeSet<Integer> childQuery = searchDown(child, methodKey, mi.getLevel());
					ret.addAll(childQuery);
				});
			}
		} else {
			ci.getChildren().forEach(child->{
				TreeSet<Integer> childQuery = searchDown(child, methodKey, level);
				ret.addAll(childQuery);
			});
		}
		
		return ret;
	}
}
