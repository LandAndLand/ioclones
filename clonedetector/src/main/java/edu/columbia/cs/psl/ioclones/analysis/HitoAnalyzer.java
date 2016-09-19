package edu.columbia.cs.psl.ioclones.analysis;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;

import edu.columbia.cs.psl.ioclones.instrument.DynFlowObserver;
import edu.columbia.cs.psl.ioclones.pojo.ClassInfo;
import edu.columbia.cs.psl.ioclones.pojo.MethodInfo;
import edu.columbia.cs.psl.ioclones.utils.ClassDataTraverser;
import edu.columbia.cs.psl.ioclones.utils.ClassInfoUtils;
import edu.columbia.cs.psl.ioclones.utils.GlobalInfoRecorder;
import edu.columbia.cs.psl.ioclones.utils.IOUtils;

public class HitoAnalyzer {
	
	private static final Logger logger = LogManager.getLogger(HitoAnalyzer.class);
	
	private static final Options options = new Options();
	
	static {
		options.addOption("src", true, "source codebase");
		options.getOption("src").setRequired(true);
		options.addOption("dest", true, "instrumentation destination");
		options.getOption("dest").setRequired(true);
	}
	
	public static void main(String[] args) throws ParseException {
		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = parser.parse(options, args);
		String codebase = null;
		
		codebase = cmd.getOptionValue("src");
		if (codebase == null) {
			System.err.println("Please specify source codebase");
			System.exit(-1);
		}
		File sourceDir = new File(codebase);
		if (!sourceDir.exists()) {
			System.err.println("Invalid source codebase: " + sourceDir.getAbsolutePath());
			System.exit(-1);
		}
		logger.info("Source codebase: " + codebase);
		
		String dest = null;
		dest = cmd.getOptionValue("dest");
		if (dest == null) {
			System.err.println("Please specify destination folder");
			System.exit(-1);
		}
		File destDir = new File(dest);
		if (!destDir.exists()) {
			destDir.mkdir();
		}
		logger.info("Destination: " + destDir.getAbsolutePath());
		
	}
	
	private static void processClass(File f, File outputDir) {
		try {
			String name = f.getName();
			InputStream is = new FileInputStream(f);
			byte[] classData = ClassDataTraverser.cleanClass(is);
			
			ClassReader cr = new ClassReader(classData);
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw) {
				String internalClassName;
				
				String className;
				
				String superName;
				
				@Override
				public void visit(int version, 
						int access, 
						String name, 
						String signature, 
						String superName, 
						String[] interfaces) {
					super.visit(version, access, name, signature, superName, interfaces);
					this.internalClassName = name;
					this.className = ClassInfoUtils.cleanType(name);
					if (superName != null) {
						this.superName = ClassInfoUtils.cleanType(superName);
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
					boolean isInterface = ClassInfoUtils.checkAccess(access, Opcodes.ACC_INTERFACE);
					boolean isAbstract = ClassInfoUtils.checkAccess(access, Opcodes.ACC_ABSTRACT);
					
					MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
					if (name.equals("toString") && desc.equals("()Ljava/lang/String;")) {
						return mv;
					} else if (name.equals("equals") && desc.equals("(Ljava/lang/Object;)Z")) {
						return mv;
					} else if (name.equals("hashCode") && desc.equals("()I")) {
						return mv;
					} else if (isNative || isInterface || isAbstract) {
						return mv;
					} else {
						DynFlowObserver dfo = new DynFlowObserver(this.internalClassName, 
								this.className, 
								access, 
								name, 
								desc, 
								signature, 
								exceptions, 
								mv);
						LocalVariablesSorter lvs = new LocalVariablesSorter(access, desc, dfo);
						dfo.setLocalVariableSorter(lvs);
						return dfo.getLocalVariablesSorter();
					}
				}
			};

			FileOutputStream fos = new FileOutputStream(outputDir.getPath() + File.separator + name);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			lastInstrumentedClass = outputDir.getPath() + File.separator + name;

			if (ANALYZE_ONLY)
				analyzeClass(is);
			else {
				byte[] c = instrumentClass(outputDir.getAbsolutePath(), is, true);
				bos.write(c);
				bos.writeTo(fos);
				fos.close();
			}
			is.close();

		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private static void processDirectory(File f, File parentOutputDir, boolean isFirstLevel) {
		if (f.getName().equals(".AppleDouble"))
			return;
		File thisOutputDir;
		if (isFirstLevel) {
			thisOutputDir = parentOutputDir;
		} else {
			thisOutputDir = new File(parentOutputDir.getAbsolutePath() + File.separator + f.getName());
			thisOutputDir.mkdir();
		}
		for (File fi : f.listFiles()) {
			if (fi.isDirectory())
				processDirectory(fi, thisOutputDir, false);
			else if (fi.getName().endsWith(".class"))
				processClass(fi, thisOutputDir);
			else if (fi.getName().endsWith(".jar") || fi.getName().endsWith(".zip") || fi.getName().endsWith(".war"))
				processZip(fi, thisOutputDir);
			else {
				File dest = new File(thisOutputDir.getPath() + File.separator + fi.getName());
				FileChannel source = null;
				FileChannel destination = null;

				try {
					source = new FileInputStream(fi).getChannel();
					destination = new FileOutputStream(dest).getChannel();
					destination.transferFrom(source, 0, source.size());
				} catch (Exception ex) {
					System.err.println("error copying file " + fi);
					ex.printStackTrace();
					//					logger.log(Level.SEVERE, "Unable to copy file " + fi, ex);
					//					System.exit(-1);
				} finally {
					if (source != null) {
						try {
							source.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					if (destination != null) {
						try {
							destination.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
	}

	/**
	 * Handle Jar file, Zip file and War file
	 */
	public static void processZip(File f, File outputDir) {
		try {
			ZipFile zip = new ZipFile(f);
			ZipOutputStream zos = null;
			zos = new ZipOutputStream(new FileOutputStream(outputDir.getPath() + File.separator + f.getName()));
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();

				if (e.getName().endsWith(".class")) {
					if (ANALYZE_ONLY)
						analyzeClass(zip.getInputStream(e));
					else {
						try {
							ZipEntry outEntry = new ZipEntry(e.getName());
							zos.putNextEntry(outEntry);

							byte[] clazz = instrumentClass(f.getAbsolutePath(), zip.getInputStream(e), true);
							if (clazz == null) {
								System.out.println("Failed to instrument " + e.getName() + " in " + f.getName());
								InputStream is = zip.getInputStream(e);
								byte[] buffer = new byte[1024];
								while (true) {
									int count = is.read(buffer);
									if (count == -1)
										break;
									zos.write(buffer, 0, count);
								}
								is.close();
							} else
								zos.write(clazz);
							zos.closeEntry();
						} catch (ZipException ex) {
							ex.printStackTrace();
							continue;
						}
					}
				} else if (e.getName().endsWith(".jar")) {
					ZipEntry outEntry = new ZipEntry(e.getName());

					Random r = new Random();
					String markFileName = Long.toOctalString(System.currentTimeMillis())
						+ Integer.toOctalString(r.nextInt(10000))
						+ e.getName().replace("/", "");
					File tmp = new File("/tmp/" + markFileName);
					if (tmp.exists())
						tmp.delete();
					FileOutputStream fos = new FileOutputStream(tmp);
					byte buf[] = new byte[1024];
					int len;
					InputStream is = zip.getInputStream(e);
					while ((len = is.read(buf)) > 0) {
						fos.write(buf, 0, len);
					}
					is.close();
					fos.close();

					File tmp2 = new File("/tmp/tmp2");
					if(!tmp2.exists())
						tmp2.mkdir();
					processZip(tmp, tmp2);
					tmp.delete();

					zos.putNextEntry(outEntry);
					is = new FileInputStream("/tmp/tmp2/" + markFileName);
					byte[] buffer = new byte[1024];
					while (true) {
						int count = is.read(buffer);
						if (count == -1)
							break;
						zos.write(buffer, 0, count);
					}
					is.close();
					zos.closeEntry();
				} else {
					ZipEntry outEntry = new ZipEntry(e.getName());
					if (e.isDirectory()) {
						try{
							zos.putNextEntry(outEntry);
							zos.closeEntry();
						} catch(ZipException exxxx) {
							System.out.println("Ignoring exception: " + exxxx.getMessage());
						}
					} else if (e.getName().startsWith("META-INF")
							&& (e.getName().endsWith(".SF")
								|| e.getName().endsWith(".RSA"))) {
						// don't copy this
					} else if (e.getName().equals("META-INF/MANIFEST.MF")) {
						Scanner s = new Scanner(zip.getInputStream(e));
						zos.putNextEntry(outEntry);

						String curPair = "";
						while (s.hasNextLine()) {
							String line = s.nextLine();
							if (line.equals("")) {
								curPair += "\n";
								if (!curPair.contains("SHA1-Digest:"))
									zos.write(curPair.getBytes());
								curPair = "";
							} else {
								curPair += line + "\n";
							}
						}
						s.close();
						// Jar file is different from Zip file. :)
						if (f.getName().endsWith(".zip"))
							zos.write("\n".getBytes());
						zos.closeEntry();
					} else {
						try {

						zos.putNextEntry(outEntry);
						InputStream is = zip.getInputStream(e);
						byte[] buffer = new byte[1024];
						while (true) {
							int count = is.read(buffer);
							if (count == -1)
								break;
							zos.write(buffer, 0, count);
						}
						is.close();
						zos.closeEntry();
						} catch (ZipException ex) {
							if (!ex.getMessage().contains("duplicate entry")) {
								ex.printStackTrace();
								System.out.println("Ignoring above warning from improper source zip...");
							}
						}
					}
				}
			}

			if (zos != null)
				zos.close();
			zip.close();
		} catch (Exception e) {
			System.err.println("Unable to process zip/jar: " + f.getAbsolutePath());
			e.printStackTrace();
			File dest = new File(outputDir.getPath() + File.separator + f.getName());
			FileChannel source = null;
			FileChannel destination = null;

			try {
				source = new FileInputStream(f).getChannel();
				destination = new FileOutputStream(dest).getChannel();
				destination.transferFrom(source, 0, source.size());
			} catch (Exception ex) {
				System.err.println("Unable to copy zip/jar: " + f.getAbsolutePath());
				ex.printStackTrace();
			} finally {
				if (source != null) {
					try {
						source.close();
					} catch (IOException e2) {
						e2.printStackTrace();
					}
				}
				if (destination != null) {
					try {
						destination.close();
					} catch (IOException e2) {
						e2.printStackTrace();
					}
				}
			}
		}
	}
	
	
}

