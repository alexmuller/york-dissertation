package uk.ac.york.module_allocation.web;

import gurobi.*;

import java.text.DecimalFormat;
import java.util.*;
import javax.persistence.EntityManager;
import org.springframework.*;
import uk.ac.york.module_allocation.*;

/**
 * Allocates modules to students based on their rankings
 * 1. Drops all allocations for that department in the DB
 * 2. Runs the Gurobi Optimizer to get new allocations
 * 3. Persists them to the DB
 * 
 * @author Alex Muller
 */
@RequestMapping("/admin/allocate")
@Controller
public class AdminAllocate {
  @Autowired
  private GRBEnv gurobiEnv;
  
  @RequestMapping(method = RequestMethod.POST)
  public String index(@RequestParam(value = "hist_val") String hist_val) {
    
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    final String username = auth.getName();
    Department dept = Staff.getStaffFromUsername(username).getDepartment();
    
    final String grbvar_module_prefix = "moduledma";
    
    // Drop every allocation in the DB for this department
    int deletedCount = Allocation.deleteByDepartment(dept);
    
    int num_allocations = 0;
    
    // This is a map relating rank-by-student to an arbitrary coefficient
    // Coefficients were decided in conjunction with departments to ensure
    // they got the allocation they wanted
    Map<Short, Integer> choicescoeff = new HashMap<Short, Integer>();
    choicescoeff.put(new Short("1"),  new Integer("2048"));
    choicescoeff.put(new Short("2"),  new Integer("1000"));
    choicescoeff.put(new Short("3"),  new Integer("480"));
    choicescoeff.put(new Short("4"),  new Integer("235"));
    choicescoeff.put(new Short("5"),  new Integer("115"));
    choicescoeff.put(new Short("6"),  new Integer("55"));
    choicescoeff.put(new Short("7"),  new Integer("26"));
    choicescoeff.put(new Short("8"),  new Integer("12"));
    choicescoeff.put(new Short("9"),  new Integer("6"));
    choicescoeff.put(new Short("10"), new Integer("5"));
    choicescoeff.put(new Short("11"), new Integer("4"));
    choicescoeff.put(new Short("12"), new Integer("3"));
    choicescoeff.put(new Short("13"), new Integer("2"));
    choicescoeff.put(new Short("14"), new Integer("1"));
    
    // Only select students/modules for this specific department
    Set<Student> students = dept.getStudents();
    List<DMA> modules = DMA.findDmasByDepartment(dept);
    List<Choice> choices = Choice.findAllChoices();
    List<Elective> electives = Elective.findAllElectives();
    Map<StudMod, Short> choicesmap = Choice.findAllChoicesAsMap();
    
    // Next, transform electives into a map indexed by (student, group)
    Map<StudGroup, Short> electivesmap = new HashMap<StudGroup, Short>();
    for (Elective elective : electives) {
      Student student = elective.getStudent();
      ModulesGroup group = elective.getModulesgroup();
      Short credits = elective.getCredits();
      StudGroup sg = new StudGroup(student, group);
      electivesmap.put(sg, credits);
    }
    
    // The Gurobi fun starts right here
    try {
      
      GRBModel  model = new GRBModel(gurobiEnv);
      
      // The following block creates one var for every module that every student
      // can take. This is every module that appears on every student's sheet.
      Map<StudMod, GRBVar> gurobimavs = new HashMap<StudMod, GRBVar>();
      for (Student student : students) {
        Set<ModulesGroup> mgs = student.getRoute_code().getModulegroups();
        Iterator mgs_iterator = mgs.iterator();
        while (mgs_iterator.hasNext()) {
          ModulesGroup mg = (ModulesGroup) mgs_iterator.next();
          // Get modules in this group
          Set<MIMG> minmgs = mg.getModulesinthisgroup();
          Iterator minmgs_iterator = minmgs.iterator();
          // Iterate over set of modules in modules group elements
          while (minmgs_iterator.hasNext()) {
            MIMG minmg = (MIMG) minmgs_iterator.next();
            
            // Create gurobimavs
            
            StudMod sm = new StudMod(student, minmg.getDept_module());
            Student this_student = (Student) sm.getStudent();
            String this_student_id = this_student.getUsername();
            DMA this_dma = (DMA) sm.getModule();
            String this_dma_id = this_dma.getId().toString();
            
            String name = this_student_id + "_" + this_dma_id;
            GRBVar grbvar = model.addVar(0.0, 1.0, 1.0, GRB.BINARY, name);
            gurobimavs.put(sm, grbvar);
          }
        }
      }
      
      // This block creates one binary variable for every module.
      // It indicates whether the module will run or not.
      Map<DMA, GRBVar> gurobimods = new HashMap<DMA, GRBVar>();
      for (DMA module : modules) {
        String name = grbvar_module_prefix + module.getId().toString();
        GRBVar grbvar = model.addVar(0.0, 1.0, 1.0, GRB.BINARY, name);
        gurobimods.put(module, grbvar);
      }
      
      // Update the variables we've added to the model
      model.update();
      
      // The objective function is the thing we want to maximise/minimise
      // Add the student's rank value as the coefficient
      GRBLinExpr objfn = new GRBLinExpr();
      for (Map.Entry<StudMod, GRBVar> entry : gurobimavs.entrySet()) {
        
        StudMod this_key = entry.getKey();
        
        Student this_student = (Student) this_key.getStudent();
        String this_student_id = this_student.getUsername();
        DMA this_dma = (DMA) this_key.getModule();
        String this_dma_id = this_dma.getId().toString();
        
        GRBVar this_gurobimav = entry.getValue();
        
        try {
          Short this_choice = choicesmap.get(this_key);
          Integer this_coeff = choicescoeff.get(this_choice);
          if (this_coeff == null) {
            this_coeff = 0;
          }
          objfn.addTerm(this_coeff, this_gurobimav);
        } catch (NullPointerException e) {
          // Module not ranked for some reason? It's worth 0 in the function.
          objfn.addTerm(0.0, this_gurobimav);
        }
        
        // First hard constraint
        // A student can only take a module if it is running
        GRBVar this_gurobimod = gurobimods.get(this_dma);
        String name = "modrunning_" + this_student_id + "_" + this_dma_id;
        model.addConstr(this_gurobimav, GRB.LESS_EQUAL, this_gurobimod, name);
      }
      
      // We are attempting to maximise the function (remember, a 1st choice
      // has a bigger coefficient than a 2nd choice, etc etc)
      model.setObjective(objfn, GRB.MAXIMIZE);
      
      // Second hard constraint
      // Every module must be the correct size (min-max) or else it does not run
      for (DMA module : modules) {
        
        GRBLinExpr sum_students = new GRBLinExpr();
        for (Map.Entry<StudMod, GRBVar> entry : gurobimavs.entrySet()) {
          DMA grb_dma = (DMA) entry.getKey().getModule();
          if (module == grb_dma) {
            GRBVar this_gurobimav = entry.getValue();
            sum_students.addTerm(1.0, this_gurobimav);
          }
        }
        
        short student_min = module.getStudent_min();
        short student_cap = module.getStudent_cap();
        
        // sum_students in this module must be <= module_max
        String name = "classmax_" + module.getId();
        model.addConstr(sum_students, GRB.LESS_EQUAL, student_cap, name);
        
        GRBLinExpr x = new GRBLinExpr();
        x.addTerm(1.0, gurobimods.get(module));
        
        // sum_students = sum_students + (-student_min * x)
        sum_students.multAdd(-student_min, x);
        
        // So what we're saying is, if the module is running (x is 1):
        // sum_students - student_min >= 0
        // sum_students >= student_min
        name = "classmin_" + module.getId();
        model.addConstr(sum_students, GRB.GREATER_EQUAL, 0.0, name);
        
      }
      
      // Third hard constraint
      // Every student must take the correct number of credits from every
      // group they can see, discounting elective credits
      for (Student student : students) {
        Set<ModulesGroup> mgs = student.getRoute_code().getModulegroups();
        Iterator mgs_iterator = mgs.iterator();
        while (mgs_iterator.hasNext()) {
          ModulesGroup mg = (ModulesGroup) mgs_iterator.next();
          // Electives get subtracted, here, something a bit like this:
          StudGroup sg = new StudGroup(student, mg);
          Short electives = electivesmap.get(sg);
          if (electives == null) {
            electives = 0;
          }
          // Continue:
          GRBLinExpr creds_allocated = new GRBLinExpr();
          // Get modules in this group
          Set<MIMG> minmgs = mg.getModulesinthisgroup();
          Iterator minmgs_iterator = minmgs.iterator();
          // Iterate over set of modules in modules group elements
          while (minmgs_iterator.hasNext()) {
            MIMG minmg = (MIMG) minmgs_iterator.next();
            DMA module = minmg.getDept_module();
            short module_credits = module.getCredit();
            StudMod sm = new StudMod(student, module);
            creds_allocated.addTerm(module_credits, gurobimavs.get(sm));
          }
          short creds_to_alloc = mg.getcreds_to_alloc();
          String name = "cr_" + student.getUsername() + "_" + mg.getId();
          model.addConstr(creds_allocated, GRB.EQUAL, creds_to_alloc - electives, name);
        }
        
      }
      
      // Fourth hard constraint (this one's a bit of a test)
      // No student can do too badly - cap sum of ranks
      // Only really applies to the History department
      if (dept.getDepartment_name().equals("History")) {
        try {
          final int history_ss_cap = Integer.valueOf(hist_val);
          int history_joint_cap = history_ss_cap / (int) 2;
          
          for (Student student : students) {
            String name = "equality_" + student.getUsername();
            Set<ModulesGroup> mgs = student.getRoute_code().getModulegroups();
            Iterator mgs_iterator = mgs.iterator();
            GRBLinExpr lhs = new GRBLinExpr();
            while (mgs_iterator.hasNext()) {
              ModulesGroup mg = (ModulesGroup) mgs_iterator.next();
              Set<MIMG> minmgs = mg.getModulesinthisgroup();
              Iterator minmgs_iterator = minmgs.iterator();
              while (minmgs_iterator.hasNext()) {
                MIMG minmg = (MIMG) minmgs_iterator.next();
                DMA module = minmg.getDept_module();
                StudMod sm = new StudMod(student, module);
                lhs.addTerm(choicesmap.get(sm), gurobimavs.get(sm));
              }
            }
            if (student.getRoute_code().getRoute_code().equals("UBHISSHIS3")) {
              model.addConstr(lhs, GRB.LESS_EQUAL, history_ss_cap, name);
            } else {
              model.addConstr(lhs, GRB.LESS_EQUAL, history_joint_cap, name);
            }
          }
          
          
        } catch (NumberFormatException e) {
          e.printStackTrace();
        }
      }
      
      // Other hard constraints specific to departments
      // No interface to record these due to a lack of time
      // 1. No History student can take both HIS00024I and HIS00026I
      if (dept.getDepartment_name().equals("History")) {
        for (Student student : students) {
          String name = "excl_" + student.getUsername();
          DMA dma1 = DeptModule.getDmaFromModuleCode("HIS00024I");
          DMA dma2 = DeptModule.getDmaFromModuleCode("HIS00026I");
          StudMod sm1 = new StudMod(student, dma1);
          StudMod sm2 = new StudMod(student, dma2);
          GRBLinExpr lhs = new GRBLinExpr();
          lhs.addTerm(1.0, gurobimavs.get(sm1));
          lhs.addTerm(1.0, gurobimavs.get(sm2));
          model.addConstr(lhs, GRB.LESS_EQUAL, 1.0, name);            
        }
      }
      
      model.optimize(); // Find a solution given the constraints above
      
      // Persist the variables to the DB
      
      GRBVar[] fvars  = model.getVars();
      double[] x    = model.get(GRB.DoubleAttr.X, fvars);
      String[] vnames = model.get(GRB.StringAttr.VarName, fvars);
      
      List<String> modules_running = new ArrayList<String>();
      List<String> modules_cancelled = new ArrayList<String>();
      for (int j = 0; j < fvars.length; j++) {
        if (vnames[j].startsWith(grbvar_module_prefix)) {
          // This is a binary var indicating 1/0 if the module runs
        } else {
          // This is a binary var indicating 1/0 if we have an allocation
          if (x[j] == 1.0) {
            // If it's a 1.0, create a new allocation and persist it
            Allocation alloc = new Allocation();
            String stud_username = vnames[j].split("_")[0];
            String dma_id = vnames[j].split("_")[1];
            Student student = Student.getStudentFromUsername(stud_username);
            DMA dma = DMA.findDMA(Long.parseLong(dma_id));
            alloc.setStudent(student);
            alloc.setDeptmodule(dma);
            alloc.persist();
            num_allocations += 1;
          }
        }
      }
      
      model.dispose();    
    } catch (GRBException e) {
      e.printStackTrace();
    }
    
    return "admin/allocate";
  }

}
