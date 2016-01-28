package edu.columbia.cs.psl.ioclones.analysis;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;

import edu.columbia.cs.psl.ioclones.driver.IODriver;
import edu.columbia.cs.psl.ioclones.pojo.ClassInfo;
import edu.columbia.cs.psl.ioclones.pojo.MethodInfo;
import edu.columbia.cs.psl.ioclones.utils.ClassInfoUtils;
import edu.columbia.cs.psl.ioclones.utils.GlobalInfoRecorder;

public class PreAnalyzer {
	
	private static final Logger logger = LogManager.getLogger(PreAnalyzer.class);
	
	private static final String rtJarPath = "/Library/Java/JavaVirtualMachines/jdk1.8.0_65.jdk/Contents/Home/jre/lib/rt.jar";
	
	public static void main(String[] args) {
		List<InputStream> container = new ArrayList<InputStream>();
		String profileName = null;
		if (args.length == 0) {
			logger.info("JVM profiling mode");
			profileName = "jvm_profile";
			
			/*String jreLibPath = System.getProperty("sun.boot.class.path");
			String[] jreLibs = jreLibPath.split(":");
			
			for (String s: jreLibs) {
				System.out.println(s);
				
				File jarFile = new File(s);
				if (!jarFile.exists()) {
					logger.warn("Invalid jar path: " + s);
					continue ;
				}
				
				ClassInfoUtils.collectClassesInJar(jarFile, container);
			}*/
			File rtFile = new File(rtJarPath);
			ClassInfoUtils.collectClassesInJar(rtFile, container);
			logger.info("Total collected jvm class file: " + container.size());
		} else {
			String codebasePath = args[0];
			int lastIdx = codebasePath.lastIndexOf("/");
			if (lastIdx == -1) {
				profileName = codebasePath;
			} else {
				profileName = codebasePath.substring(lastIdx + 1, codebasePath.length());
			}
			
			File codebase = new File(args[0]);
			
			if (!codebase.exists()) {
				logger.error("Invalid codebase: " + codebase.getAbsolutePath());
				System.exit(-1);
			}
			
			logger.info("Profiling: " + codebase.getAbsolutePath());
			logger.info("Profile name: " + profileName);
			ClassInfoUtils.genRepoClasses(codebase, container);
			logger.info("Total collected classes in codebase: " + container.size());
		}
		
		System.out.println("Classes to analyze: " + container.size());
		try {
			for (InputStream is: container) {					
				ClassReader cr = new ClassReader(is);
				ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
					@Override
					public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
						// TODO Auto-generated method stub
						return new JSRInlinerAdapter(super.visitMethod(access, name, desc, signature, exceptions), access, name, desc, signature, exceptions);
					}
				}, ClassReader.EXPAND_FRAMES);
				
				ClassReader analysisReader = new ClassReader(cw.toByteArray());
				ClassWriter analysisWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, analysisWriter) {
					String className;
					
					String superName;
					
					ClassInfo classInfo;
					
					@Override
					public void visit(int version, 
							int access, 
							String name, 
							String signature, 
							String superName, 
							String[] interfaces) {
						super.visit(version, access, name, signature, superName, interfaces);
						
						this.className = ClassInfoUtils.cleanType(name);
						//logger.info("Name: " + this.className);
						
						this.classInfo = GlobalInfoRecorder.queryClassInfo(this.className);
						if (this.classInfo == null) {
							this.classInfo = new ClassInfo(this.className);
							GlobalInfoRecorder.registerClassInfo(this.classInfo);
						}
						
						if (superName != null) {
							this.superName = ClassInfoUtils.cleanType(superName);
							ClassInfo superClass = GlobalInfoRecorder.queryClassInfo(this.superName);
							if (superClass == null) {
								superClass = new ClassInfo(this.superName);
								GlobalInfoRecorder.registerClassInfo(superClass);
							}
							
							this.classInfo.setParent(this.superName);
							superClass.addChild(this.className);
						}
						
						for (String inter: interfaces) {
							inter = ClassInfoUtils.cleanType(inter);
							ClassInfo interClass = GlobalInfoRecorder.queryClassInfo(inter);
							if (interClass == null) {
								interClass = new ClassInfo(inter);
								GlobalInfoRecorder.registerClassInfo(interClass);
							}
							
							this.classInfo.addInterface(inter);
							interClass.addChild(this.className);
						}
					}
					
					@Override
					public MethodVisitor visitMethod(int access, 
							String name, 
							String desc, 
							String signature, 
							String[] exceptions) {
						boolean isSynthetic = ClassInfoUtils.checkAccess(access, Opcodes.ACC_SYNTHETIC);
						boolean isNative = ClassInfoUtils.checkAccess(access, Opcodes.ACC_NATIVE);
						//boolean isInterface = ClassInfoUtils.checkAccess(access, Opcodes.ACC_INTERFACE);
						//boolean isAbstract = ClassInfoUtils.checkAccess(access, Opcodes.ACC_ABSTRACT);
						
						MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
						if (name.equals("toString") && desc.equals("()Ljava/lang/String;")) {
							return mv;
						} else if (name.equals("equals") && desc.equals("(Ljava/lang/Object;)Z")) {
							return mv;
						} else if (name.equals("hashCode") && desc.equals("()I")) {
							return mv;
						} else if (isSynthetic || isNative) {
							return mv;
						} else {
							WriterExplorer we = new WriterExplorer(mv, 
									access, 
									this.className, 
									name, 
									desc, 
									signature, 
									exceptions, 
									this.classInfo);
							return we;
						}
					}
				};
				analysisReader.accept(cv, ClassReader.EXPAND_FRAMES);
			}
		} catch (Exception ex) {
			logger.error("Error: ", ex);
		}
		
		Map<String, ClassInfo> allClasses = GlobalInfoRecorder.getClassInfo();
		int methodCounter = 0;
		int stabelizedCounter = 0;
		for (String className: allClasses.keySet()) {
			ClassInfo clazz = allClasses.get(className);
			Map<String, MethodInfo> methods = clazz.getMethods();
			methodCounter += methods.size();
			for (MethodInfo method: methods.values()) {
				//Identify which methods are already stable (no callees)
				int callSize = method.getCallees().size();
				if (callSize == 0) {
					method.stabelized = true;
					stabelizedCounter++;
					//SymbolicValueAnalyzer.analyzeValue(method);
				}
			}
		}
		
		logger.info("Total method count: " + methodCounter);
		logger.info("Stabelized count: " + stabelizedCounter);
		
		logger.info("Review profiling results of methods");
		allClasses.forEach((name, clazz)->{
			logger.info("Class: " + clazz.getClassName());
			clazz.getMethods().forEach((key, m)->{
				logger.info("Method: " + m.getMethodKey());
				logger.info("Stabelized: " + m.stabelized);
				m.getCallees().forEach(c->{
					logger.info("Callee: " + c.getMethodKey());
					logger.info("Potential outputs: " + c.getPotentialOutputs());
				});
			});
		});
		GlobalInfoRecorder.reportClassProfiles(IODriver.profileDir, profileName);
		
		//Start to summarize the methods with their callees...
		/*System.out.println("Not stabelize methods");
		notStable.forEach(n->{
			System.out.println(n.getMethodKey());
			ClassInfoUtils.stabelizeMethod(n);
		});*/
	}
	
	public static class WriterExplorer extends MethodVisitor {
		
		public int access;
		
		public String className;
		
		public String methodName;
		
		public String methodDesc;
		
		//public Map<Integer, Boolean> paramMap = new HashMap<Integer, Boolean>();
		
		public WriterExplorer(MethodVisitor mv, 
				int access, 
				String className, 
				String methodName, 
				String methodDesc, 
				String signature, 
				String[] exceptions,
				ClassInfo ownerClass) {
			super(Opcodes.ASM5, new MethodNode(Opcodes.ASM5, 
					access, 
					methodName, 
					methodDesc, 
					signature, 
					exceptions) {
				
				@Override
				public void visitEnd() {
					String[] parsed = ClassInfoUtils.genMethodKey(className, methodName, methodDesc);
					String methodKey = parsed[0];
					
					boolean isFinal = ClassInfoUtils.checkAccess(access, Opcodes.ACC_FINAL);
					int level = -1;
					boolean isPublic = ClassInfoUtils.checkAccess(access, Opcodes.ACC_PUBLIC);
					if (!isPublic) {
						boolean isProtected = ClassInfoUtils.checkAccess(access, Opcodes.ACC_PROTECTED);
						if (!isProtected) {
							boolean isPrivate = ClassInfoUtils.checkAccess(access, Opcodes.ACC_PRIVATE);
							if (!isPrivate) {
								level = MethodInfo.DEFAULT;
							} else {
								level = MethodInfo.PRIVATE;
							}
						} else {
							level = MethodInfo.PROTECTED;
						}
					} else {
						level = MethodInfo.PUBLIC;
					}
					
					//logger.info("Method key: " + methodKey);
					MethodInfo info = new MethodInfo(methodKey);
					info.setLevel(level);
					info.setFinal(isFinal);
					ownerClass.addMethod(info);
					
					boolean isStatic = ClassInfoUtils.checkAccess(this.access, Opcodes.ACC_STATIC);
					Type[] args = null;
					if (isStatic) {
						args = Type.getArgumentTypes(this.desc);
					} else {
						Type[] methodArgs = Type.getArgumentTypes(this.desc);
						args = new Type[methodArgs.length + 1];
						args[0] = Type.getObjectType(className);
						for (int i = 1; i < args.length; i++) {
							args[i] = methodArgs[i - 1];
						}
					}
					Type returnType = Type.getReturnType(this.desc);
					
					//DependentValueInterpreter dvi = new DependentValueInterpreter(args, returnType, info);
					ExploreValueInterpreter fvi = new ExploreValueInterpreter(args, returnType, info);
					Analyzer a = new Analyzer(fvi);
					try {
						//Analyze callee here
						Frame[] fr = a.analyze(className, this);
						//info.insts = this.instructions;
						//info.frames = fr;
						//info.dvi = fvi;
						fvi.getParamList().forEach(val->{
							if (val.getDeps() != null && val.getDeps().size() > 0) {
								
							}
						});
						
						for (int j = 0; j < fvi.getParamList().size(); j++) {
							DependentValue val = fvi.getParamList().get(j);
							if (val.getDeps() != null && val.getDeps().size() > 0) {
								if (info.writtenInputs == null) {
									info.writtenInputs = new TreeSet<Integer>();
								}
								
								info.writtenInputs.add(j);
							}
						}
					} catch (Exception ex) {
						logger.info("Error: ", ex);
					}
				}
			});
		}
	}
	
	public static class ObjInitCollector extends MethodVisitor {
		
		public LinkedList<String> recorder = null;
		
		public Set<String> constraints = null;
		
		public ObjInitCollector(MethodVisitor mv, 
				LinkedList<String> recorder, 
				Set<String> constraints) {
			super(Opcodes.ASM5, mv);
			this.recorder = recorder;
			this.constraints = constraints;
		}
		
		@Override
		public void visitTypeInsn(int opcode, String type) {
			this.mv.visitTypeInsn(opcode, type);
			
			if (opcode == Opcodes.NEW) {
				if (this.constraints != null) {
					if (!this.constraints.contains(type) && !this.recorder.contains(type)) {
						this.recorder.add(type);
					}
				} else  {
					if (!this.recorder.contains(type)) {
						this.recorder.add(type);
					}
				}
			}
		}
		
		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
			this.mv.visitMethodInsn(opcode, owner, name, desc, itf);
			
			if (name.equals("<init>")) {
				return ;
			} else if (name.equals("toString") && desc.equals("()Ljava/lang/String;")) {
				return ;
			} else if (name.equals("equals") && desc.equals("(Ljava/lang/Object;)Z")) {
				return ;
			} else if (name.equals("hashCode") && desc.equals("()I")) {
				return ;
			}
			
			String cleanOwner = ClassInfoUtils.cleanType(owner);
			if (this.constraints != null) {
				if (!this.constraints.contains(cleanOwner) && !this.recorder.contains(cleanOwner)) {
					this.recorder.add(cleanOwner);
				}
			} else {
				if (!this.recorder.contains(cleanOwner))
					this.recorder.add(cleanOwner);
			}
		}
	}
}
