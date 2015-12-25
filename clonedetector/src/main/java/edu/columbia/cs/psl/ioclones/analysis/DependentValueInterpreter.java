package edu.columbia.cs.psl.ioclones.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

import edu.columbia.cs.psl.ioclones.utils.ClassInfoUtils;


public class DependentValueInterpreter extends BasicInterpreter {
	
	private static Logger logger = LogManager.getLogger(DependentValueInterpreter.class);
	
	private boolean init = false;
		
	//private Set<Integer> params = new HashSet<Integer>();
	private Map<Integer, DependentValue> params = new HashMap<Integer, DependentValue>();
	
	public Map<Integer, DependentValue> getParams() {
		return this.params;
	}
		
	@Override
	public BasicValue newValue(Type type) {
		if (type == null) {
			return BasicValue.UNINITIALIZED_VALUE;
		} else if (type.getSort() == Type.VOID) {
			return null;
		}
		DependentValue dv = new DependentValue(type);
		
		if (!this.init) {
			this.params.put(dv.id, dv);
		}
		
		return dv;
		//		return super.newValue(type);
	}
	
	@Override
	public BasicValue newOperation(final AbstractInsnNode insn) throws AnalyzerException {
		this.init = true;
		
		switch (insn.getOpcode()) {
			case ACONST_NULL:
				return newValue(Type.getObjectType("null"));
			case ICONST_M1:
			case ICONST_0:
			case ICONST_1:
			case ICONST_2:
			case ICONST_3:
			case ICONST_4:
			case ICONST_5:
				//DependentValue ret = new DependentValue(Type.INT_TYPE);
				//return ret;
				return newValue(Type.INT_TYPE);
			case LCONST_0:
			case LCONST_1:
				return newValue(Type.LONG_TYPE);
			case FCONST_0:
			case FCONST_1:
			case FCONST_2:
				return newValue(Type.FLOAT_TYPE);
			case DCONST_0:
			case DCONST_1:
				return newValue(Type.DOUBLE_TYPE);
			case BIPUSH:
			case SIPUSH:
				return newValue(Type.INT_TYPE);
			case LDC:
				Object cst = ((LdcInsnNode) insn).cst;
				if (cst instanceof Integer) {
					//return BasicValue.INT_VALUE;
					return newValue(Type.INT_TYPE);
				} else if (cst instanceof Float) {
					//return BasicValue.FLOAT_VALUE;
					return newValue(Type.FLOAT_TYPE);
				} else if (cst instanceof Long) {
					//return BasicValue.LONG_VALUE;
					return newValue(Type.LONG_TYPE);
				} else if (cst instanceof Double) {
					//return BasicValue.DOUBLE_VALUE;
					return newValue(Type.DOUBLE_TYPE);
				} else if (cst instanceof String) {
					return newValue(Type.getObjectType("java/lang/String"));
				} else if (cst instanceof Type) {
					int sort = ((Type) cst).getSort();
					if (sort == Type.OBJECT || sort == Type.ARRAY) {
						return newValue(Type.getObjectType("java/lang/Class"));
					} else if (sort == Type.METHOD) {
						return newValue(Type.getObjectType("java/lang/invoke/MethodType"));
					} else {
						throw new IllegalArgumentException("Illegal LDC constant " + cst);
					}
				} else if (cst instanceof Handle) {
					return newValue(Type.getObjectType("java/lang/invoke/MethodHandle"));
				} else {
					throw new IllegalArgumentException("Illegal LDC constant " + cst);
				}
			case JSR:
				return BasicValue.RETURNADDRESS_VALUE;
			case GETSTATIC:
				DependentValue ret = new DependentValue(Type.getType(((FieldInsnNode) insn).desc));
				//ret.src = insn;
				ret.addSrc(insn);
				//return newValue(Type.getType(((FieldInsnNode) insn).desc));
				return ret;
			case NEW:
				return newValue(Type.getObjectType(((TypeInsnNode) insn).desc));
			default:
				logger.error("Invalid new operation: " + insn);
				throw new Error("Internal error.");
		}
	}
	
	@Override
	public BasicValue copyOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
		this.init = true;
		
		DependentValue dv = (DependentValue) value;
		switch(insn.getOpcode()) {
			case Opcodes.ILOAD:
			case Opcodes.LLOAD:
			case Opcodes.FLOAD:
			case Opcodes.DLOAD:
			case Opcodes.ALOAD:
				//For capturing xloads that might be input
				if (this.params.containsKey(dv.id)) {
					System.out.println("Input load: " + insn + " " + dv);
					dv.addSrc(insn);
				} else {
					System.out.println("Check load: " + insn + " " + dv);
				}
			default:
				//return super.copyOperation(insn, value);
				return super.copyOperation(insn, dv);
		}
	}

	@Override
	public BasicValue unaryOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
		this.init= true;
		
		DependentValue oriVal = null;
		DependentValue ret = null;
		switch (insn.getOpcode()) {
			case IINC:
				if (!(value instanceof DependentValue)) {
					logger.error("Suspicious val for IINC: " + insn + " " + value);
				}
				//The value here should be dependent value
				return value;
			case INEG:
				return value;
	        case L2I:
	        case F2I:
	        case D2I:
	        case I2B:
	        case I2C:
	        case I2S:
	        case ARRAYLENGTH:
	            //return BasicValue.INT_VALUE;
	        	oriVal = (DependentValue) value;
	            ret = (DependentValue) newValue(Type.INT_TYPE);
	            ret.addDep(oriVal);
	            return ret;
	        case FNEG:
	        	return value;
	        case I2F:
	        case L2F:
	        case D2F:
	        	//return BasicValue.FLOAT_VALUE;
	        	oriVal = (DependentValue) value;
	        	ret = (DependentValue) newValue(Type.FLOAT_TYPE);
	            ret.addDep(oriVal);
	            return ret;
	        case LNEG:
	        case I2L:
	        case F2L:
	        case D2L:
	            //return BasicValue.LONG_VALUE;
	        	oriVal = (DependentValue) value;
	        	ret = (DependentValue) newValue(Type.LONG_TYPE);
	        	ret.addDep(oriVal);
	        	return ret;
	        case DNEG:
	        	return value;
	        case I2D:
	        case L2D:
	        case F2D:
	            //return BasicValue.DOUBLE_VALUE;
	            oriVal = (DependentValue) value;
	            ret = (DependentValue) newValue(Type.DOUBLE_TYPE);
	            ret.addDep(oriVal);
	            return ret;
			case Opcodes.GETFIELD:
				ret = (DependentValue) super.unaryOperation(insn, value);
				//ret.src = insn;
				ret.addSrc(insn);
				
				DependentValue owner = (DependentValue)value;
				ret.owner = owner;
				
				System.out.println("Getfield: " + insn + " " + ret);
				return ret;
			default:
				return super.unaryOperation(insn, value);
		}
	}
	
	@Override
	public BasicValue binaryOperation(final AbstractInsnNode insn, final BasicValue value1, final BasicValue value2) throws AnalyzerException {
		this.init = true;
		
		DependentValue ret = null;
		DependentValue arrRef = null;
		DependentValue idx = null;
		switch (insn.getOpcode()) {
			case IALOAD:
			case BALOAD:
			case CALOAD:
			case SALOAD:
				arrRef = (DependentValue) value1;
				idx = (DependentValue) value2;
				ret = new DependentValue(Type.INT_TYPE);
				ret.owner = arrRef;
				ret.addSrc(insn);
				return ret;
			case IADD:
			case ISUB:
			case IMUL:
			case IDIV:
			case IREM:
			case ISHL:
			case ISHR:
			case IUSHR:
			case IAND:
			case IOR:
			case IXOR:
				ret = new DependentValue(Type.INT_TYPE);
				DependentValue dep1 = (DependentValue) value1;
				DependentValue dep2 = (DependentValue) value2;
				ret.addDep(dep1);
				ret.addDep(dep2);
				return ret;
			case FALOAD:
				arrRef = (DependentValue) value1;
				idx = (DependentValue) value2;
				ret = new DependentValue(Type.FLOAT_TYPE);
				ret.owner = arrRef;
				ret.addSrc(insn);
				return ret;
			case FADD:
			case FSUB:
			case FMUL:
			case FDIV:
			case FREM:
				ret = new DependentValue(Type.FLOAT_TYPE);
				ret.addDep((DependentValue) value1);
				ret.addDep((DependentValue) value2);
				return ret;
			case LALOAD:
				arrRef = (DependentValue) value1;
				idx = (DependentValue) value2;
				ret = new DependentValue(Type.LONG_TYPE);
				ret.owner = arrRef;
				ret.addSrc(insn);
				return ret;
			case LADD:
			case LSUB:
			case LMUL:
			case LDIV:
			case LREM:
			case LSHL:
			case LSHR:
			case LUSHR:
			case LAND:
			case LOR:
			case LXOR:
				ret = new DependentValue(Type.LONG_TYPE);
				ret.addDep((DependentValue) value1);
				ret.addDep((DependentValue) value2);
				return ret;
			case DALOAD:
				arrRef = (DependentValue) value1;
				idx = (DependentValue) value2;
				ret = new DependentValue(Type.DOUBLE_TYPE);
				ret.owner = arrRef;
				ret.addSrc(insn);
				return ret;
			case DADD:
			case DSUB:
			case DMUL:
			case DDIV:
			case DREM:
				ret = new DependentValue(Type.DOUBLE_TYPE);
				ret.addDep((DependentValue) value1);
				ret.addDep((DependentValue) value2);
				//return BasicValue.DOUBLE_VALUE;
				return ret;
			case AALOAD:
				arrRef = (DependentValue) value1;
				idx = (DependentValue) value2;
				ret = new DependentValue(BasicValue.REFERENCE_VALUE.getType());
				ret.owner = arrRef;
				ret.addSrc(insn);
				//return BasicValue.REFERENCE_VALUE;
				return ret;
			case LCMP:
			case FCMPL:
			case FCMPG:
			case DCMPL:
			case DCMPG:
				ret = new DependentValue(Type.INT_TYPE);
				ret.addDep((DependentValue) value1);
				ret.addDep((DependentValue) value2);
				//return BasicValue.INT_VALUE;
				return ret;
			case IF_ICMPEQ:
			case IF_ICMPNE:
			case IF_ICMPLT:
			case IF_ICMPGE:
			case IF_ICMPGT:
			case IF_ICMPLE:
			case IF_ACMPEQ:
			case IF_ACMPNE:
				return null;
			case PUTFIELD:
				if (value2 == BasicValue.UNINITIALIZED_VALUE) {
					return null;
				}
				
				DependentValue objRef = (DependentValue) value1;
				DependentValue written = (DependentValue) value2;
				ClassInfoUtils.propagateDepToOwners(objRef, written);
				
				return null;
			default:
				throw new Error("Internal error.");
		}
	}
	
	@Override
	public BasicValue ternaryOperation(AbstractInsnNode insn, 
			BasicValue val1, 
			BasicValue val2, 
			BasicValue val3) throws AnalyzerException {
		this.init = true;
		
		DependentValue objRef = (DependentValue)val1;
		//Don't record the idx for array, too detailed
		//DependentValue idx = (DependentValue)val2;
		DependentValue val = (DependentValue)val3;
		
		objRef.addDep(val);
		ClassInfoUtils.propagateDepToOwners(objRef, val);
		val.owner = objRef;
		
		return super.ternaryOperation(insn, val1, val2, val3);
	}
	
	@Override
	public BasicValue naryOperation(AbstractInsnNode insn,
            List values) throws AnalyzerException {
		this.init = true;
		
		int opcode = insn.getOpcode();
		DependentValue ret = (DependentValue)super.naryOperation(insn, values);
		List<DependentValue> dvs = (List<DependentValue>)values;
		switch(opcode) {
			case Opcodes.INVOKESTATIC:
			case Opcodes.INVOKEVIRTUAL:
			case Opcodes.INVOKEINTERFACE:
			case Opcodes.INVOKEDYNAMIC:
				dvs.forEach(dv->{
					ret.addDep(dv);
				});
		}
		
		return super.naryOperation(insn, values);
	}
	
	@Override
	public void returnOperation(AbstractInsnNode insn, 
			BasicValue value, 
			BasicValue expected) throws AnalyzerException {
		this.init = true;
		super.returnOperation(insn, value, expected);
	}
	
	@Override
	public BasicValue merge(BasicValue v, BasicValue w) {
		System.out.println("V: " + v);
		System.out.println("W: " + w);
		if (v == BasicValue.UNINITIALIZED_VALUE 
				&& w == BasicValue.UNINITIALIZED_VALUE) {
			return v;
		}
		
		if (!(v instanceof DependentValue 
				|| w instanceof DependentValue)) {
			return super.merge(v, w);
		}	

		if (v.equals(w)) {
			return v;
		}

		if (v instanceof DependentValue && w instanceof DependentValue) {
			DependentValue sv = (DependentValue) v;
			DependentValue sw = (DependentValue) w;
			if ((v.getType() == null || v.getType().getDescriptor().equals("Lnull;")) 
					&& (w.getType() == null || w.getType().getDescriptor().equals("Lnull;"))) {
				if ((sw.getSrcs() != null && sv.getDeps() != null && sw != null && sv.getDeps().contains(sw)) 
						|| (sw.getSrcs() == null && sw.getDeps() != null && sv.getDeps() != null && sv.getDeps().containsAll(sw.getDeps())))
					return v;
				else {
					sv.addDep(sw);
					return v;
				}
			}
			
			if (v.getType() == null || v.getType().getDescriptor().equals("Lnull;")) {
				sw.addDep(sv);
				return w;
			} else if (w.getType() == null || w.getType().getDescriptor().equals("Lnull;")) {
				sv.addDep(sw);
				return v;
			} else {
				if (v.getType().equals(w.getType())) {
					sv.addDep(sw);
					return v;
				}
			}
		}
		
		if (v.getType() == null || v.getType().getDescriptor().equals("Lnull;")) {
			return w;
		} else if (w.getType() == null || w.getType().getDescriptor().equals("Lnull;")) {
			return v;
		}
		
		System.out.println("Touch merging objects");
		if(v.getType().getDescriptor().equals("Ljava/lang/Object;"))
			return v;
		
		BasicValue r = new DependentValue(Type.getType(Object.class));
		System.out.println();
		return r;
	}
}
