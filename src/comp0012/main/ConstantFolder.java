package comp0012.main;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
// these break it, if we dont need them remove
//import com.sun.org.apache.bcel.internal.classfile.Constant;
//import com.sun.org.apache.bcel.internal.generic.ConstantPushInstruction;
import java.util.*; // import everything

import org.apache.bcel.generic.*;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.util.InstructionFinder;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.StackMapTable;

public class ConstantFolder {
	ClassParser parser = null;
	ClassGen gen = null;

	JavaClass original = null;
	JavaClass optimized = null;

	private ConstantPoolGen cpgen;
	private Stack<Number> valuesStack;
	private Stack<InstructionHandle> loadInstructions;
	private HashMap<Integer, Number> variables;
	private List<InstructionHandle> loopBounds;

	private HashMap<Integer, InstructionHandle[]> variableInstructions;
	private HashMap<Integer, Boolean> variableUsed;

	public ConstantFolder(String classFilePath) {
		try {
			this.parser = new ClassParser(classFilePath);
			this.original = this.parser.parse();
			this.gen = new ClassGen(this.original);
			this.gen.setMajor(50);
			this.gen.setMinor(0);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// This method performs constant folding optimization on the bytecode.

	public void optimize() {
		// Original Don't Delete
		ClassGen cgen = new ClassGen(original); // I didn't use this shit at all
		ConstantPoolGen cpgen = gen.getConstantPool();
		// Original Don't Delete

		// Get all methods in the class
		Method[] methods = gen.getMethods();

		for (Method method : methods) {
			if (method.isAbstract() || method.isNative()) {
				continue;
			}
			processMethod(method, cpgen);
		}

		// Original Don't Delete
		this.optimized = gen.getJavaClass();
	}

	// optimize methods one by one

	private void processMethod(Method method, ConstantPoolGen cpgen) {
		ClassGen cg = new ClassGen(original);
		cg.setMajor(50);
		cg.setMinor(0);

		MethodGen methodGen = new MethodGen(method, gen.getClassName(), cpgen);
		InstructionList il = methodGen.getInstructionList();
		if (il == null) {
			return; // Skip methods without instructions.
		}

		System.out.println("=== Processing method: " + method.getName() + " ===");

		System.out.println("Before Optimization:");
		for (InstructionHandle handle = il.getStart(); handle != null; handle = handle.getNext()) {
			System.out.println(handle.getInstruction());
		}

		// Iteratively optimize until no more changes occur.
		boolean madeChanges;
		int iteration = 0;
		do {
			iteration++;
			System.out.println("Iteration " + iteration + " of optimizations.");

			boolean varFoldChanged = foldVariables(methodGen);
			boolean constFoldChanged = optimizeInstructions(il, cpgen);
			madeChanges = varFoldChanged || constFoldChanged;

			if (madeChanges) {
				il.setPositions(true); // Update the positions after changes.
			}
		} while (madeChanges);

		// Remove debugging info so outdated stack maps are not used.
		methodGen.removeLineNumbers();
		methodGen.removeLocalVariables();

		// Recompute max stack and locals.
		methodGen.setMaxStack();
		methodGen.setMaxLocals();

		Method optimizedMethod = methodGen.getMethod();

		// Remove outdated stack map attributes.
		List<Attribute> newAttrs = new ArrayList<>();
		for (Attribute attr : optimizedMethod.getAttributes()) {
			String attrName = attr.getName();
			if (!attrName.equals("StackMapTable") && !attrName.equals("StackMap")) {
				newAttrs.add(attr);
			} else {
				System.out.println("Removing outdated stack map attribute: " + attrName);
			}
		}
		optimizedMethod.setAttributes(newAttrs.toArray(new Attribute[newAttrs.size()]));

		// Replace the original method if any changes were made.
		if (iteration > 1) { // If more than one iteration occurred assume modifications were made.
			gen.replaceMethod(method, optimizedMethod);
			System.out.println("Method " + method.getName() + " replaced with optimized version.");
		} else {
			System.out.println("No modifications applied to method " + method.getName());
		}
	}

	private boolean optimizeInstructions(InstructionList il, ConstantPoolGen cpgen) {
		InstructionFinder finder = new InstructionFinder(il);
		// Define pattern for constant arithmetic operations
		String pattern = "LDC LDC IADD|LDC LDC ISUB|LDC LDC IMUL|LDC LDC IDIV|LDC LDC IREM|" + // ints
				"LDC2_W LDC2_W LADD|LDC2_W LDC2_W LSUB|LDC2_W LDC2_W LMUL|LDC2_W LDC2_W LDIV|LDC2_W LDC2_W LREM|" + // longs
				"LDC LDC FADD|LDC LDC FSUB|LDC LDC FMUL|LDC LDC FDIV|LDC LDC FREM|" + // floats
				"LDC2_W LDC2_W DADD|LDC2_W LDC2_W DSUB|LDC2_W LDC2_W DMUL|LDC2_W LDC2_W DDIV|LDC2_W LDC2_W DREM"; // doubles

		boolean optimized = false;
		boolean madeChanges;
		do {
			madeChanges = false;
			for (Iterator<InstructionHandle[]> it = finder.search(pattern); it.hasNext();) {
				InstructionHandle[] match = it.next();
				if (match.length == 3) {
					if (tryConstantFolding(match[0], match[1], match[2], cpgen, il)) {
						madeChanges = true;
						optimized = true;
						break; // Restart the search with the modified instruction list
					}
				}
			}
		} while (madeChanges);
		return optimized;
	}

	private boolean tryConstantFolding(InstructionHandle first, InstructionHandle second,
			InstructionHandle third, ConstantPoolGen cpgen, InstructionList il) {
		Instruction inst1 = first.getInstruction();
		Instruction inst2 = second.getInstruction();
		Instruction inst3 = third.getInstruction();

		Number result = null;
		Instruction newInst = null;
		int index;

		// Check for int/float constant folding (using LDC)
		if (inst1 instanceof LDC && inst2 instanceof LDC && inst3 instanceof ArithmeticInstruction) {
			LDC ldc1 = (LDC) inst1;
			LDC ldc2 = (LDC) inst2;
			Object value1 = ldc1.getValue(cpgen);
			Object value2 = ldc2.getValue(cpgen);

			if (!(value1 instanceof Number && value2 instanceof Number)) {
				return false;
			}
			Number num1 = (Number) value1;
			Number num2 = (Number) value2;

			// Integer operations
			if (inst3 instanceof IADD) {
				result = num1.intValue() + num2.intValue();
			} else if (inst3 instanceof ISUB) {
				result = num1.intValue() - num2.intValue();
			} else if (inst3 instanceof IMUL) {
				result = num1.intValue() * num2.intValue();
			} else if (inst3 instanceof IDIV && num2.intValue() != 0) {
				result = num1.intValue() / num2.intValue();
			} else if (inst3 instanceof IREM && num2.intValue() != 0) {
				result = num1.intValue() % num2.intValue();
			}
			// Float operations
			else if (inst3 instanceof FADD) {
				result = num1.floatValue() + num2.floatValue();
			} else if (inst3 instanceof FSUB) {
				result = num1.floatValue() - num2.floatValue();
			} else if (inst3 instanceof FMUL) {
				result = num1.floatValue() * num2.floatValue();
			} else if (inst3 instanceof FDIV && num2.floatValue() != 0) {
				result = num1.floatValue() / num2.floatValue();
			} else if (inst3 instanceof FREM && num2.floatValue() != 0) {
				result = num1.floatValue() % num2.floatValue();
			}

			if (result == null) {
				return false;
			}

			// Create new LDC instruction for int or float constant folding
			if (result instanceof Integer) {
				index = cpgen.addInteger(result.intValue());
				newInst = new LDC(index);
			} else if (result instanceof Float) {
				index = cpgen.addFloat(result.floatValue());
				newInst = new LDC(index);
			} else {
				return false;
			}
		}
		// Check for long/double constant folding (using LDC2_W)
		else if (inst1 instanceof LDC2_W && inst2 instanceof LDC2_W && inst3 instanceof ArithmeticInstruction) {
			LDC2_W ldc1 = (LDC2_W) inst1;
			LDC2_W ldc2 = (LDC2_W) inst2;
			Object value1 = ldc1.getValue(cpgen);
			Object value2 = ldc2.getValue(cpgen);

			if (!(value1 instanceof Number && value2 instanceof Number)) {
				return false;
			}
			Number num1 = (Number) value1;
			Number num2 = (Number) value2;

			// Long operations
			if (inst3 instanceof LADD) {
				result = num1.longValue() + num2.longValue();
			} else if (inst3 instanceof LSUB) {
				result = num1.longValue() - num2.longValue();
			} else if (inst3 instanceof LMUL) {
				result = num1.longValue() * num2.longValue();
			} else if (inst3 instanceof LDIV && num2.longValue() != 0) {
				result = num1.longValue() / num2.longValue();
			} else if (inst3 instanceof LREM && num2.longValue() != 0) {
				result = num1.longValue() % num2.longValue();
			}
			// Double operations
			else if (inst3 instanceof DADD) {
				result = num1.doubleValue() + num2.doubleValue();
			} else if (inst3 instanceof DSUB) {
				result = num1.doubleValue() - num2.doubleValue();
			} else if (inst3 instanceof DMUL) {
				result = num1.doubleValue() * num2.doubleValue();
			} else if (inst3 instanceof DDIV && num2.doubleValue() != 0) {
				result = num1.doubleValue() / num2.doubleValue();
			} else if (inst3 instanceof DREM && num2.doubleValue() != 0) {
				result = num1.doubleValue() % num2.doubleValue();
			}

			if (result == null) {
				return false;
			}

			// Create new LDC2_W instruction for long or double constant folding
			if (result instanceof Long) {
				index = cpgen.addLong(result.longValue());
				newInst = new LDC2_W(index);
			} else if (result instanceof Double) {
				index = cpgen.addDouble(result.doubleValue());
				newInst = new LDC2_W(index);
			} else {
				return false;
			}
		} else {
			return false;
		}

		try {
			il.insert(first, newInst);
			il.delete(first, third);
		} catch (TargetLostException e) {
			return false;
		}
		return true;
	}

	/**
	 * Performs simple constant propagation and arithmetic folding for integer
	 * values.
	 * Returns true if any changes are made.
	 */
	public static boolean foldVariables(MethodGen methodGen) {
		boolean changesMade = false;
		InstructionList il = methodGen.getInstructionList();
		ConstantPoolGen cp = methodGen.getConstantPool();
		InstructionHandle[] handles = il.getInstructionHandles();

		// Map local variable index to its constant value if available.
		Map<Integer, Number> constantMap = new HashMap<>();

		// A simple forward pass; note that a complete implementation would require full
		// CFG analysis.
		for (InstructionHandle handle : handles) {
			Instruction inst = handle.getInstruction();

			// Handle storing an int constant into a local variable.
			if (inst instanceof ISTORE) {
				ISTORE store = (ISTORE) inst;
				int index = store.getIndex();
				InstructionHandle prevHandle = handle.getPrev();
				if (prevHandle != null) {
					Instruction prevInst = prevHandle.getInstruction();
					// If the preceding instruction is an LDC that loads a constant, record the
					// constant.
					if (prevInst instanceof LDC) {
						LDC ldc = (LDC) prevInst;
						Object value = ldc.getValue(cp);
						if (value instanceof Integer) {
							constantMap.put(index, (Integer) value);
						} else {
							constantMap.remove(index);
						}
					} else {
						// The value wasn't produced by an immediate constant load.
						constantMap.remove(index);
					}
				}
			}
			// Replace a load of an int variable with its constant (if available).
			else if (inst instanceof ILOAD) {
				ILOAD load = (ILOAD) inst;
				int index = load.getIndex();
				if (constantMap.containsKey(index)) {
					Number constant = constantMap.get(index);
					Instruction ldcInstr = null;
					// Only handling Integer for now.
					if (constant instanceof Integer) {
						// Add the integer constant to the constant pool and create an LDC instruction.
						int cpIndex = cp.addInteger(((Integer) constant).intValue());
						ldcInstr = new LDC(cpIndex);
					}
					if (ldcInstr != null) {
						try {
							// Insert the new LDC instruction before the ILOAD and delete the ILOAD.
							il.insert(handle, ldcInstr);
							il.delete(handle);
							changesMade = true;
						} catch (TargetLostException e) {
							e.printStackTrace();
						}
					}
				}
			}
			// Constant fold arithmetic operation: For example, folding an IADD if both
			// operands are constants.
			else if (inst instanceof IADD) {
				InstructionHandle prev1 = handle.getPrev();
				if (prev1 == null)
					continue;
				InstructionHandle prev2 = prev1.getPrev();
				if (prev2 == null)
					continue;
				Instruction i1 = prev2.getInstruction();
				Instruction i2 = prev1.getInstruction();
				if (i1 instanceof LDC && i2 instanceof LDC) {
					LDC ldc1 = (LDC) i1;
					LDC ldc2 = (LDC) i2;
					Object val1 = ldc1.getValue(cp);
					Object val2 = ldc2.getValue(cp);
					if (val1 instanceof Integer && val2 instanceof Integer) {
						int result = ((Integer) val1).intValue() + ((Integer) val2).intValue();
						// Create a new LDC with the folded result.
						int cpIndex = cp.addInteger(result);
						Instruction newLdc = new LDC(cpIndex);
						try {
							// Insert the new constant instruction and remove the two LDC instructions and
							// the IADD.
							il.insert(prev2, newLdc);
							il.delete(prev2);
							il.delete(prev1);
							il.delete(handle);
							changesMade = true;
						} catch (TargetLostException e) {
							e.printStackTrace();
						}
					}
				}
			}
			// Extend with additional cases (ISUB, IMUL, etc.) as needed.
		}

		// Finalize the instruction list: reset positions and update stack/local sizes.
		il.setPositions();
		methodGen.setMaxStack();
		methodGen.setMaxLocals();
		return changesMade;
	}

	public void write(String optimisedFilePath) {
		this.optimize();

		try {
			FileOutputStream out = new FileOutputStream(new File(optimisedFilePath));
			this.optimized.dump(out);
		} catch (FileNotFoundException e) {
			// Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}
	}
}
