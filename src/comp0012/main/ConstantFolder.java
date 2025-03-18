package comp0012.main;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ArithmeticInstruction;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.DADD;
import org.apache.bcel.generic.DDIV;
import org.apache.bcel.generic.DMUL;
import org.apache.bcel.generic.DREM;
import org.apache.bcel.generic.DSUB;
import org.apache.bcel.generic.FADD;
import org.apache.bcel.generic.FDIV;
import org.apache.bcel.generic.FMUL;
import org.apache.bcel.generic.FREM;
import org.apache.bcel.generic.FSUB;
import org.apache.bcel.generic.IADD;
import org.apache.bcel.generic.IDIV;
import org.apache.bcel.generic.IMUL;
import org.apache.bcel.generic.IREM;
import org.apache.bcel.generic.ISUB;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InstructionTargeter;
import org.apache.bcel.generic.LADD;
import org.apache.bcel.generic.LDC;
import org.apache.bcel.generic.LDC2_W;
import org.apache.bcel.generic.LDIV;
import org.apache.bcel.generic.LMUL;
import org.apache.bcel.generic.LREM;
import org.apache.bcel.generic.LSUB;
import org.apache.bcel.util.InstructionFinder;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.TargetLostException;

public class ConstantFolder {
	ClassParser parser = null;
	ClassGen gen = null;

	JavaClass original = null;
	JavaClass optimized = null;

	public ConstantFolder(String classFilePath) {
		try {
			this.parser = new ClassParser(classFilePath);
			this.original = this.parser.parse();
			this.gen = new ClassGen(this.original);
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

		// Loop through all the methods (looking at executable one only e.g. LDC)
		for (int i = 0; i < methods.length; i++) {
			Method method = methods[i];

			if (method.isAbstract() || method.isNative()) {
				continue;
			}

			// This is how we can modify the method bytecode
			MethodGen methodGen = new MethodGen(method, gen.getClassName(), cpgen);

			// Get the instruction list that contains the bytecode instructions
			InstructionList instructionList = methodGen.getInstructionList();
			if (instructionList == null) {
				continue; // skip w/o instructions
			}

			// This is instruction finder very useful to find specific instruction pattern
			// No need for some convoluted logic
			InstructionFinder finder = new InstructionFinder(instructionList);

			// Define patterns for finding constant arithmetic operations
			// I use gpt to generate this part so I dont think there mssing one
			// But I double check so there should be 4 data type and 2 of them act
			// differently
			String pattern = "LDC LDC IADD|LDC LDC ISUB|LDC LDC IMUL|LDC LDC IDIV|LDC LDC IREM|" +
					"LDC2_W LDC2_W LADD|LDC2_W LDC2_W LSUB|LDC2_W LDC2_W LMUL|LDC2_W LDC2_W LDIV|LDC2_W LDC2_W LREM|" +
					"LDC LDC FADD|LDC LDC FSUB|LDC LDC FMUL|LDC LDC FDIV|LDC LDC FREM|" +
					"LDC2_W LDC2_W DADD|LDC2_W LDC2_W DSUB|LDC2_W LDC2_W DMUL|LDC2_W LDC2_W DDIV|LDC2_W LDC2_W DREM";

			boolean optimized = false; // Track if any optimizations were made
			boolean madeChanges; // Track changes in each iteration

			// Loop until no more optimizations can be done
			do {
				madeChanges = false;

				// Search for all occurrences of the pattern in the instruction list
				for (Iterator<InstructionHandle[]> it = finder.search(pattern); it.hasNext();) {
					InstructionHandle[] match = it.next();

					if (match.length == 3) {
						InstructionHandle first = match[0]; // First load instruction
						InstructionHandle second = match[1]; // Second load instruction
						InstructionHandle third = match[2]; // Arithmetic instruction

						Instruction inst1 = first.getInstruction();
						Instruction inst2 = second.getInstruction();
						Instruction inst3 = third.getInstruction();

						// Handle integer and float using LDC instruction
						if (inst1 instanceof LDC && inst2 instanceof LDC && inst3 instanceof ArithmeticInstruction) {
							// Get their values
							LDC ldc1 = (LDC) inst1;
							LDC ldc2 = (LDC) inst2;

							// Get the actual constant values from the constant pool
							Object value1 = ldc1.getValue(cpgen);
							Object value2 = ldc2.getValue(cpgen);

							if (value1 instanceof Number && value2 instanceof Number) {
								Number num1 = (Number) value1;
								Number num2 = (Number) value2;
								Number result = null;

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

								// Create new instruction for it
								if (result != null) {
									Instruction newInst;
									int index;

									// Add to the constant pool
									if (result instanceof Integer) {
										index = cpgen.addInteger(result.intValue());
										newInst = new LDC(index);
									} else if (result instanceof Float) {
										index = cpgen.addFloat(result.floatValue());
										newInst = new LDC(index);
									} else {
										continue;
									}

									try {
										instructionList.insert(first, newInst);
										instructionList.delete(first, third);
										madeChanges = true;
										optimized = true;
										break; // Restart the search with the newly changed instruction list
									} catch (TargetLostException e) {
										continue;
									}
								}
							}
						}
						// Handle long and double arithmetic usubg LDC2_W
						else if (inst1 instanceof LDC2_W && inst2 instanceof LDC2_W
								&& inst3 instanceof ArithmeticInstruction) {
							LDC2_W ldc1 = (LDC2_W) inst1;
							LDC2_W ldc2 = (LDC2_W) inst2;

							Object value1 = ldc1.getValue(cpgen);
							Object value2 = ldc2.getValue(cpgen);

							if (value1 instanceof Number && value2 instanceof Number) {
								Number num1 = (Number) value1;
								Number num2 = (Number) value2;
								Number result = null;

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

								if (result != null) {
									Instruction newInst;
									int index;

									if (result instanceof Long) {
										index = cpgen.addLong(result.longValue());
										newInst = new LDC2_W(index);
									} else if (result instanceof Double) {
										index = cpgen.addDouble(result.doubleValue());
										newInst = new LDC2_W(index);
									} else {
										continue;
									}

									try {
										instructionList.insert(first, newInst);
										instructionList.delete(first, third);
										madeChanges = true;
										optimized = true;
										break;
									} catch (TargetLostException e) {
										continue;
									}
								}
							}
						}
					}
				}
			} while (madeChanges); // Continue until no more changes can be made
			if (optimized) {
				methodGen.setMaxStack();
				methodGen.setMaxLocals();

				// Replace the original with the optimized version
				gen.replaceMethod(method, methodGen.getMethod());
			}
		}

		// Original Don't Delete
		this.optimized = gen.getJavaClass();
		// Original Don't Delete
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