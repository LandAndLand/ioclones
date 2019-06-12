package edu.columbia.cs.psl.ioclones;

import java.io.Flushable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;

import edu.columbia.cs.psl.ioclones.analysis.DependentValue;
import edu.columbia.cs.psl.ioclones.analysis.DependentValueInterpreter;
import edu.columbia.cs.psl.ioclones.pojo.ParamInfo;
import edu.columbia.cs.psl.ioclones.utils.ClassInfoUtils;

public class SiteAnalyzer extends MethodVisitor {
	
	private static final Logger logger = LogManager.getLogger(DependencyAnalyzer.class);
	
	public static final String OUTPUT_MSG = "__$$COLUMBIA_IO_OUTPUT";
	
	public static final String TAINTED_IN = "__$$COLUMBIA_IO_TAINT@";
	
	public static final String TAINTED_STATIC = "__$$COLUMBIA_IO_TAINTSTATIC";
	
	public static final String INPUT_CHECK_MSG = "__$$COLUMBIA_IO_CHECK";
	
	public static final String INPUT_MSG = "__$$COLUMBIA_IO_INPUT";
	
	public static final String INPUT_COPY_0_MSG = "__$$COLUMBIA_IO_INPUT0";
	
	public static final String INPUT_COPY_1_MSG = "__$$COLUMBIA_IO_INPUT1";
	
	public static final String INPUT_COPY_2_MSG = "__$$COLUMBIA_IO_INPUT2";
	
	public SiteAnalyzer(final String className, 
			int access, 
			final String name, 
			final String desc, 
			String signature, 
			String[] exceptions, 
			final MethodVisitor cmv, 
			Set<AbstractInsnNode> readInputParams,
			Set<AbstractInsnNode> readNonInputParams,
			boolean trackStatic, 
			boolean trackWriter, 
			boolean debug) {
		
		super(Opcodes.ASM5, 
				new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions) {
												
			@Override
			public void visitEnd() {
				logger.info("Analyzing " + className + " " + name + " " + desc);
				
				//BlockAnalyzer blockAnalyzer = new BlockAnalyzer(className, name, desc, this.instructions);
				//blockAnalyzer.constructBlocks();
				
				boolean isStatic = ClassInfoUtils.checkAccess(access, Opcodes.ACC_STATIC);
				Type[] args = null;
				if (isStatic) {
					args = ClassInfoUtils.genMethodArgs(this.desc, null);
				} else {
					args = ClassInfoUtils.genMethodArgs(this.desc, className);
				}
				Type returnType = Type.getReturnType(this.desc);
				List<ParamInfo> paramInfos = ClassInfoUtils.computeMethodArgs(args);
				
				String methodNameArgs = ClassInfoUtils.methodNameArgs(name, desc);
				DependentValueInterpreter dvi = new DependentValueInterpreter(args, 
						returnType, 
						className, 
						methodNameArgs, 
						true, 
						true, 
						trackStatic, 
						trackWriter);
				
				Analyzer a = new Analyzer(dvi);
				try {
					Frame[] fr = a.analyze(className, this);
					
					Set<AbstractInsnNode> writeStatic = new HashSet<AbstractInsnNode>();
					Set<AbstractInsnNode> returns = new HashSet<AbstractInsnNode>();
					
					if (!dvi.giveup) {
						Map<Integer, WrittenParam> writtenParams = new HashMap<Integer, WrittenParam>();
						List<Integer> runtimeIdx = new ArrayList<Integer>();
						//Determine which input has been written
						for (int j = 0; j < dvi.getParamList().size(); j++) {
							DependentValue paramVal = dvi.getParamList().get(j);
							runtimeIdx.add(paramInfos.get(j).runtimeIdx);
							if (paramVal.written) {
								LinkedList<DependentValue> writtenDeps = paramVal.tag();
								
								if (writtenDeps.size() > 0) {
									writtenDeps.removeFirst();
								}
								
								WrittenParam wp = new WrittenParam();
								wp.paramIdx = paramInfos.get(j).runtimeIdx;
								wp.deps = writtenDeps;
															
								writtenParams.put(paramVal.id, wp);
							}
						}
						
						//Only for value flows to Flushable & Appendable
						//Output vals cannot be captured here, do it in instrumenter
						Map<AbstractInsnNode, LinkedList<DependentValue>> flowToWriters = 
								new HashMap<AbstractInsnNode, LinkedList<DependentValue>>();
						
						//Only for written static...
						Map<DependentValue, LinkedList<DependentValue>> writtenStatics = 
								new HashMap<DependentValue, LinkedList<DependentValue>>();
						
						Map<DependentValue, LinkedList<DependentValue>> ios = 
								new HashMap<DependentValue, LinkedList<DependentValue>>();
						AbstractInsnNode insn = this.instructions.getFirst();
						int i = 0;
						
						while(insn != null) {
							Frame fn = fr[i];
							if(fn != null) {					
								//does this insn create output?
								int opcode = insn.getOpcode();
								if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
									//What are we returning?
									DependentValue retVal = (DependentValue)fn.getStack(fn.getStackSize() - 1);
									if (!writtenParams.containsKey(retVal.id)) {
										LinkedList<DependentValue> toOutput = retVal.tag();
										retVal.addOutSink(insn);
										
										if (retVal.getOutSinks() != null) {
											returns.addAll(retVal.getOutSinks());
										}
										
										//The first will be the ret itself
										if (toOutput.size() > 0) {
											toOutput.removeFirst();
											ios.put(retVal, toOutput);
										} else {
											//This means that this output has been analyzed before (merge)
											logger.warn("Visited val: " + retVal);
											logger.warn("Corresponding inst: " + insn);
										}
									}
								} else if (trackStatic && opcode == Opcodes.PUTSTATIC) {
									DependentValue retVal = (DependentValue)fn.getStack(fn.getStackSize() - 1);
									if (!writtenParams.containsKey(retVal.id)) {
										LinkedList<DependentValue> toOutput = retVal.tag();
										retVal.addOutSink(insn);
										
										//The first will be the ret itself
										if (toOutput.size() > 0) {
											toOutput.removeFirst();
											
											if (retVal.isReference() 
													&& !ClassInfoUtils.isImmutable(retVal.getType())) {
												writtenStatics.put(retVal, toOutput);
											} else {
												ios.put(retVal, toOutput);
											}
										} else {
											//This means that this output has been analyzed before (merge)
											logger.warn("Visited val for static: " + retVal);
											logger.warn("Corresponding inst: " + insn);
										}
									}
								} else if (trackWriter && 
										(opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE)) {
									MethodInsnNode methodInsn = (MethodInsnNode) insn;
									
									if (!methodInsn.name.equals("<init>")) {
										Type[] methodArgs = Type.getArgumentTypes(methodInsn.desc);
										if (methodArgs.length > 0) {
											String ownerName = methodInsn.owner.replace("/", ".");
											boolean writable = ClassInfoUtils.isWritable(ownerName);
											if (writable) {
												//logger.info("Capture writable: " + ownerName);
												int ptr = fn.getStackSize() - 1;
												int counter = 0;
												while (counter < methodArgs.length) {
													DependentValue dv = (DependentValue)fn.getStack(ptr - counter);
													counter++;
													
													LinkedList<DependentValue> deps = dv.tag();
													if (deps.size() > 0) {
														deps.removeFirst();
													}
													flowToWriters.put(insn, deps);
												}
											}
										}
									}
								}
							}
							i++;
							insn = insn.getNext();
						}
						
						if (debug) {
							this.debug(fr);
						}
						
						//Set<Integer> touched = new HashSet<Integer>();
						Set<AbstractInsnNode> visitedInInsns = new HashSet<AbstractInsnNode>();
						if (ios.size() > 0 || writtenParams.size() > 0) {
							//Need to analyze which control instruction should be recorded, jumps will affect outputs
							//logger.info("Cand. single controls: " + dvi.getSingleControls().size());
							dvi.getSingleControls().forEach((sc, val)->{
								this.instructions.insertBefore(sc, new LdcInsnNode(INPUT_MSG));
							});
							
							//logger.info("Cand. double controls: " + dvi.getDoubleControls().size());
							dvi.getDoubleControls().forEach((dc, vals)->{
								this.instructions.insertBefore(dc, new LdcInsnNode(INPUT_COPY_2_MSG));
							});
						}
						
						for (DependentValue o: ios.keySet()) {
							LinkedList<DependentValue> inputs = ios.get(o);
							
							if (o.getOutSinks() != null) {
								o.getOutSinks().forEach(sink->{
									this.instructions.insertBefore(sink, new LdcInsnNode(OUTPUT_MSG));
									
									int opcode = sink.getOpcode();
									if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
										returns.add(sink);
									} else if (opcode == Opcodes.PUTSTATIC) {
										writeStatic.add(sink);
									}
 								});
								//touched.add(o.id);
							} else {
								logger.warn("Empty inst for output: " + o);
							}
							
							if (inputs != null) {
								for (DependentValue input: inputs) {
									if (input.getInSrcs() == null 
											|| input.getInSrcs().size() == 0) {
										continue ;
									}
									
									input.getInSrcs().forEach(src->{
										if (!visitedInInsns.contains(src)) {
											this.instructions.insertBefore(src, new LdcInsnNode(INPUT_MSG));
											visitedInInsns.add(src);
											
											int opcode = src.getOpcode();
											if (opcode >= 21 && opcode <= 45) {
												VarInsnNode varNode = (VarInsnNode) src;
												int localIdx = varNode.var;
												if (runtimeIdx.contains(localIdx)) {
													readInputParams.add(src);
												} else {
													readNonInputParams.add(src);
												}
											} else {
												readNonInputParams.add(src);
											}
										}
									});
									//touched.add(input.id);
								}
							}
						}
											
						StringBuilder writtenBuilder = new StringBuilder();
						for (WrittenParam wp: writtenParams.values()) {
							writtenBuilder.append(wp.paramIdx + "-");
							
							for (DependentValue dv: wp.deps) {
								if (dv.getInSrcs() == null 
										|| dv.getInSrcs().size() == 0) {
									continue ;
								}
								
								dv.getInSrcs().forEach(src->{
									if (!visitedInInsns.contains(src)) {
										this.instructions.insertBefore(src, new LdcInsnNode(INPUT_MSG));
										visitedInInsns.add(src);
										
										int opcode = src.getOpcode();
										if (opcode >= 21 && opcode <= 45) {
											VarInsnNode varNode = (VarInsnNode) src;
											int localIdx = varNode.var;
											if (runtimeIdx.contains(localIdx)) {
												readInputParams.add(src);
											} else {
												readNonInputParams.add(src);
											}
										} else {
											readNonInputParams.add(src);
										}
									}
								});
							}
						}
						
						if (writtenBuilder.length() > 0) {
							String writtenMsg = writtenBuilder.substring(0, writtenBuilder.length() - 1);
							writtenMsg = TAINTED_IN + writtenMsg;
							this.instructions.insert(new LdcInsnNode(writtenMsg));
						}
						
						if (trackStatic) {
							for (DependentValue wStatic: writtenStatics.keySet()) {
								LinkedList<DependentValue> sDeps = writtenStatics.get(wStatic);
								
								wStatic.getOutSinks().forEach(sSink->{
									this.instructions.insertBefore(sSink, new LdcInsnNode(TAINTED_STATIC));
								});
								
								if (sDeps != null) {
									for (DependentValue sDep: sDeps) {
										if (sDep.getInSrcs() == null || sDep.getInSrcs().size() == 0) {
											continue ;
										}
										
										sDep.getInSrcs().forEach(sIn->{
											if (!visitedInInsns.contains(sIn)) {
												this.instructions.insertBefore(sIn, new LdcInsnNode(INPUT_MSG));
												visitedInInsns.add(sIn);
											}
											
											int opcode = sIn.getOpcode();
											if (opcode >= 21 && opcode <= 45) {
												VarInsnNode varNode = (VarInsnNode) sIn;
												int localIdx = varNode.var;
												if (runtimeIdx.contains(localIdx)) {
													readInputParams.add(sIn);
												} else {
													readNonInputParams.add(sIn);
												}
											} else {
												readNonInputParams.add(sIn);
											}
										});
									}
								}
							}
						}
						
						if (trackWriter) {
							for (AbstractInsnNode writeMethod: flowToWriters.keySet()) {
								this.instructions.insertBefore(writeMethod, new LdcInsnNode(OUTPUT_MSG));
								
								LinkedList<DependentValue> fw = flowToWriters.get(writeMethod);
								for (DependentValue fwd: fw) {
									if (fwd.getInSrcs() == null || fwd.getInSrcs().size() == 0) {
										continue ;
									}
									
									fwd.getInSrcs().forEach(fwdIn->{
										if (visitedInInsns.contains(fwdIn)) {
											this.instructions.insertBefore(fwdIn, new LdcInsnNode(INPUT_MSG));
											visitedInInsns.add(fwdIn);
										}
										
										int opcode = fwdIn.getOpcode();
										if (opcode >= 21 && opcode <= 45) {
											VarInsnNode varNode = (VarInsnNode) fwdIn;
											int localIdx = varNode.var;
											if (runtimeIdx.contains(localIdx)) {
												readInputParams.add(fwdIn);
											} else {
												readNonInputParams.add(fwdIn);
											}
										} else {
											readNonInputParams.add(fwdIn);
										}
									});
								}
							}
						}
						
						for (int j = 0; j < dvi.getParamList().size(); j++) {
							DependentValue inputParam = dvi.getParamList().get(j);
							if (inputParam.isReference() 
									&& !ClassInfoUtils.isImmutable(inputParam.getType())) {
								if (!isStatic && j == 0) {
									continue ;
								}
								
								if (inputParam.getInSrcs() != null 
										&& inputParam.getInSrcs().size() > 0) {
									inputParam.getInSrcs().forEach(check->{
										this.instructions.insert(check, new LdcInsnNode(INPUT_CHECK_MSG));
									});
								}
							}
						}
					} else {
						logger.warn("Incomplete: " +  className + " " + name + " " + desc);
						logger.warn("Inst/callee size: " + this.instructions.size() + " " + dvi.calleeNum);
					}
				} catch (Exception ex) {
					//ex.printStackTrace();
					logger.error("Error: ", ex);
				}
				
				super.visitEnd();
				this.accept(cmv);
			}
			
			public void debug(Frame[] fr) {
				//print debug info
				AbstractInsnNode insn = this.instructions.getFirst();
				int i = 0;
				while(insn != null) {
					Frame fn = fr[i];
					if(fn != null) {
						String stack = "Stack: ";
						for(int j = 0; j < fn.getStackSize(); j++) {
							stack += fn.getStack(j)+ " ";
						}
							
						String locals = "Locals ";
						for(int j = 0; j < fn.getLocals(); j++) {
							locals += fn.getLocal(j) + " ";
						}

						this.instructions.insertBefore(insn, new LdcInsnNode(stack));
						this.instructions.insertBefore(insn, new LdcInsnNode(locals));
					}
					i++;
					insn = insn.getNext();
				}
			}
		});
	}
	
	public static class WrittenParam {
		int paramIdx;
				
		List<DependentValue> deps;
	}
}
