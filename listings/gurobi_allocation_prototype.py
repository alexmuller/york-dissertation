#!/usr/bin/python

from gurobipy import *
from printtable import *
import random, string

print "=========================================================="
print "============ MODULE ALLOCATION: GUROBI SOLVER ============"
print "=========================================================="

# Generates fake University of York usernames
# e.g. Strings of the form xx###
def generate_username():
  letters = "".join(random.choice(string.ascii_lowercase) for x in range(2))
  numbers = "".join(random.choice(string.digits) for x in range(3))
  return letters + numbers

# Takes a dictionary with tuples as the key, prints a 2D table using printtable.py
def dicttable(dict, table_title):
  stud = set()
  mod = set()

  for x in sorted(dict.iterkeys()):
    stud.add(x[0])
    mod.add(x[1])

  stud = sorted(stud)
  header = [""] + stud
  mod = sorted(mod)

  allocs = []

  for m in mod:
    alloc = [m]
    for s in stud:
      try:
        alloc.append(dict[s,m])
      except KeyError:
        alloc.append('')
    allocs.append(alloc)

  table = [header] + allocs
  
  print "\n"
  print table_title
  pprint_table(out, table)

# Maps from rank -> coefficient
rank_coeff = {
  0: 0,
  1: 120,
  2: 65,
  3: 30,
  4: 5,
  5: 1,
  6: 0,
  7: 0
}

students = {
  'am639': {'routecode': 'UBARCSART3'}
}

for i in range(14):
  username = generate_username()
  students[username] = {'routecode': 'UBARCSART3'}

# sheets = ['UBCOMAMAT4']

groups = {
  'group1': {'routecode': 'UBARCSART3', 'credits': 10}#,
  # 'group2': {'routecode': 'UBCOMAMAT4', 'credits': 20}
}

modules = [
  {'code': 'ARC00032I', 'group': 'group1', 'credits': 10, 'min': 5, 'max': 100},
  {'code': 'ARC00021I', 'group': 'group1', 'credits': 10, 'min': 5, 'max': 100},
  {'code': 'ARC00020I', 'group': 'group1', 'credits': 10, 'min': 5, 'max': 100},
  {'code': 'ARC00018I', 'group': 'group1', 'credits': 10, 'min': 5, 'max': 100}#,
  # {'code': 'PR3', 'group': 'group2', 'credits': 20, 'min': 7, 'max': 20},
]

## Print the setup config

print ":: MODULE SETUP ::"

for group in groups:
  print group + " (Route code: " + groups[group]['routecode'] + ", credits to be allocated: " + str(groups[group]['credits']) + ")"
  for module in modules:
    if module['group'] == group:
      print "  " + module['code'] + " (Credits: " + str(module['credits']) + ", class size: " + str(module['min']) + "-" + str(module['max']) + ")"

print "\n\n\n"


mavs = []

for student in students:
  for group in groups:
    if groups[group]['routecode'] == students[student]['routecode']:
      for module in modules:
        if module['group'] == group:
          mavs.append((student, module['code']))

ranking_prob = 0.5
ranks = {}

for mav in mavs:
  if (random.random() < ranking_prob):
    ranks[mav] = random.randint(1, 5)


gurobimod_prefix = "zzz-mod_"

try:
  
  model = Model("modalloc")
  
  gurobimavs = {}
  for mav in mavs:
    stringrep = mav[0] + "_" + mav[1]
    gurobimavs[stringrep] = model.addVar(vtype=GRB.BINARY, name=stringrep)
  
  gurobimods = {}
  for module in modules:
    stringrep = gurobimod_prefix + module['code']
    gurobimods[stringrep] = model.addVar(vtype=GRB.BINARY, name=stringrep)
  
  model.update()
  
  # Objective function
  objfn = LinExpr()
  
  for mav in mavs:
    stringrep = mav[0] + "_" + mav[1]
    try:
      objfn.addTerms(rank_coeff[ranks[mav[0],mav[1]]], gurobimavs[stringrep])
    except KeyError:
      objfn.addTerms(0, gurobimavs[stringrep])
  
  model.setObjective(objfn, GRB.MAXIMIZE)
  
  # Hard constraint: student can only take module if it is running
  for mav in mavs:
    for module in modules:
      mav_stringrep = mav[0] + "_" + module['code']
      mod_stringrep = gurobimod_prefix + module['code']
      model.addConstr(gurobimavs[mav_stringrep] <= gurobimods[mod_stringrep],
                      "modrunning")
  
  # Hard constraint: correct class size
  for module in modules:
    mod_stringrep = gurobimod_prefix + module['code']
    mylhs = LinExpr()
    for student in students:
      stringrep = student + "_" + module['code']
      try:
        mylhs.addTerms(1, gurobimavs[stringrep])
      except KeyError:
        pass
    # model.addConstr(module['min'] <= mylhs, "classmin") # lower bound
    model.addConstr(mylhs <= module['max'], "classmax") # upper bound
    model.addConstr(mylhs - (module['min'] * gurobimods[mod_stringrep]) >= 0,
                    "classmin")
  
  # Hard constraint: correct number of credits
  for student in students:
    for group in groups:
      mylhs = LinExpr()
      for module in modules:
        if group == module['group']:
          stringrep = student + "_" + module['code']
          try:
            mylhs.addTerms(module['credits'], gurobimavs[stringrep])
          except KeyError:
            pass
      model.addConstr(mylhs == groups[group]['credits'], "credits")
  
  # Optimise!
  model.optimize()
  
  results = {}
  
  for v in model.getVars():
    results[v.varName.split("_")[0],v.varName.split("_")[1]] = v.x
  
  dicttable(ranks, ":: RANKINGS ::")
  dicttable(results, ":: RESULT ::")
  
  results_choices = {}
  
  for result in results:
    if results[result] == 1.0:
      try:
        results_choices[result] = ranks[result]
      except KeyError:
        results_choices[result] = "*"
    elif results[result] == 0.0:
      results_choices[result] = " "
  
  dicttable(results_choices, ":: RESULTS WITH RANKINGS ::")
  
  print "Obj:", model.objVal
  
except GurobiError:
  print "\nWe've encountered a GurobiError\n"
