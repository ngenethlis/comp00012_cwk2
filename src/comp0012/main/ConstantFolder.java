package comp0012.main;

import java.io.*;
import java.util.*;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
import org.apache.bcel.util.InstructionFinder;
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

public
class ConstantFolder {
  ClassParser parser = null;
  ClassGen gen = null;

  JavaClass original = null;
  JavaClass optimized = null;

public
  ConstantFolder(String classFilePath) {
    try {
      this.parser = new ClassParser(classFilePath);
      this.original = this.parser.parse();
      this.gen = new ClassGen(this.original);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // This method performs constant folding optimization on the bytecode.

public
   void optimize() {
    // Original Don't Delete
    ClassGen cgen = new ClassGen(original); // I didn't use this shit at all
    ConstantPoolGen cpgen = gen.getConstantPool();
    // Original Don't Delete

    Method[] methods = gen.getMethods();

    for (Method method : methods) {
      if (method.isAbstract() || method.isNative())
        continue;

      MethodGen methodGen = new MethodGen(method, gen.getClassName(), cpgen);
      InstructionList instructionList = methodGen.getInstructionList();
      if (instructionList == null)
        continue;

      Map<Integer, Number> constants = new HashMap<>();
      optimizeArithmeticExpressions(instructionList, cpgen); // part1
      optimizeConstantVariables(instructionList, cpgen, constants); //part2
      //optimizeArithmeticExpressions(instructionList, cpgen);

      methodGen.setMaxStack();
      methodGen.setMaxLocals();
      gen.replaceMethod(method, methodGen.getMethod());
    }

    // Original Don't Delete
    this.optimized = gen.getJavaClass();
    // Original Don't Delete
  }

private
  void optimizeConstantVariables(InstructionList il, ConstantPoolGen cpGen,
                                 Map<Integer, Number> constants) {
    for (InstructionHandle ih : il.getInstructionHandles()) {
      Instruction inst = ih.getInstruction();
      if (inst instanceof LDC) {
        LDC ldc = (LDC)inst;
        if (ldc.getValue(cpGen) instanceof Number) {
          constants.put(ih.getPosition(), (Number)ldc.getValue(cpGen));
        }
      } else if (inst instanceof ISTORE || inst instanceof LSTORE ||
                 inst instanceof FSTORE || inst instanceof DSTORE) {
        int index = ((StoreInstruction)inst).getIndex();
        InstructionHandle prev = ih.getPrev();
        if (prev != null && prev.getInstruction() instanceof LDC) {
          LDC ldc = (LDC)prev.getInstruction();
          constants.put(index, (Number)ldc.getValue(cpGen));
        }
      }
    }

    for (InstructionHandle ih : il.getInstructionHandles()) {
      Instruction inst = ih.getInstruction();
      if (inst instanceof ILOAD || inst instanceof LLOAD ||
          inst instanceof FLOAD || inst instanceof DLOAD) {
        int index = ((LoadInstruction)inst).getIndex();
        if (constants.containsKey(index)) {
          Number value = constants.get(index);
          Instruction newInst = new LDC(cpGen.addInteger(value.intValue()));
          try {
            il.insert(ih, newInst);
            il.delete(ih);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
    }
  }

private
  void optimizeArithmeticExpressions(InstructionList il,
                                     ConstantPoolGen cpGen) {
    InstructionFinder finder = new InstructionFinder(il);
    String pattern =
        "LDC LDC IADD|LDC LDC ISUB|LDC LDC IMUL|LDC LDC IDIV|LDC LDC IREM|" +
        "LDC2_W LDC2_W LADD|LDC2_W LDC2_W LSUB|LDC2_W LDC2_W LMUL|LDC2_W " +
        "LDC2_W LDIV|LDC2_W LDC2_W LREM";

    boolean madeChanges;
    do {
      madeChanges = false;
      for (Iterator<InstructionHandle[]> it = finder.search(pattern);
           it.hasNext();) {
        InstructionHandle[] match = it.next();
        if (match.length != 3)
          continue;

        LDC ldc1 = (LDC)match[0].getInstruction();
        LDC ldc2 = (LDC)match[1].getInstruction();
        ArithmeticInstruction op =
            (ArithmeticInstruction)match[2].getInstruction();

        Number v1 = (Number)ldc1.getValue(cpGen);
        Number v2 = (Number)ldc2.getValue(cpGen);
        Number result = null;

        if (op instanceof IADD)
          result = v1.intValue() + v2.intValue();
        else if (op instanceof ISUB)
          result = v1.intValue() - v2.intValue();
        else if (op instanceof IMUL)
          result = v1.intValue() * v2.intValue();
        else if (op instanceof IDIV && v2.intValue() != 0)
          result = v1.intValue() / v2.intValue();
        else if (op instanceof IREM && v2.intValue() != 0)
          result = v1.intValue() % v2.intValue();

        if (result != null) {
          Instruction newInst = new LDC(cpGen.addInteger(result.intValue()));
          try {
            il.insert(match[0], newInst);
            il.delete(match[0], match[2]);
            madeChanges = true;
            break;
          } catch (TargetLostException ignored) {
          }
        }
      }
    } while (madeChanges);
  }

public
  void write(String optimizedFilePath) {
    this.optimize();

    try {
      FileOutputStream out = new FileOutputStream(new File(optimizedFilePath));
      this.optimized.dump(out);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
