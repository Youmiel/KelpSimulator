# To add a new cell, type '# %%'
# To add a new markdown cell, type '# %% [markdown]'
# %%
import csv
import random
import json
import decimal
import time
import multiprocessing
import os
import datetime

# %% [markdown]
# #### What we have known
# 
# - Random tick ticks 3 times a gametick in every subchunk, 1 block each time, distributed equally, and duplication is allowed.
#   - Therefore, the probability of a random tick selects exactly one specific block is `p = 1 / (16 ** 3)` 
# - A kelp block has 14% chance to grow 1 block if randomly ticked once.
# - A kelp block stops growing when *age* >= 25, and *age* increase 1 each time it grows.
# - Kelp obtians random *age* between [0,24] when it is placed, not growed.

# %%
class Kelp():
    growth_probability = 0.14
    seletct_probability = 1/(16**3)
    def __init__(self) -> None:
        self.init()

    def init(self):
        self.age = random.randint(0,24)
        self.start_age = self.age

    def tick(self):
        if self.age < 25 and random.random() < Kelp.growth_probability:
            self.age += 1

    def harvest(self) -> int:
        result = self.age - self.start_age
        self.init()
        return result
    
    def reset(self):
        self.harvest()

# %% [markdown]
# #### Configs:
# 
# ```
# tick_speed: int
#     # known as randomTickSpeed
# harvest_period: int
#     # gameticks of harvest period
# empty_tick: int
#     # some special cases after harvest so that the kelp cannot grow immediately after harvest. For example, water flow takes 5gt to refill the empty space created by piston.
# height_limit: int
#     # max height allowed in this farm
# grow_after_tick: bool 
#     # Fasle: 1.15~1.16.5 behavior, random tick before scheduled tick; True: 1.15- & 1.17.x behavior, random tick after schedueled tick
# kelp_count: int
#     # numbers of kelp that used in simulation
# test_time: int
#     # unit gameticks
# ```
# 

# %%
config = {
    'process_count': 4,
    'tick_speed': 3,
    'harvest_period': {         # supported types are 'continuous' and 'list'
        'type': 'continuous',
        'start': 600,
        'end': 3600,
        'step': 600
    },
    'empty_tick': {             # supported types are 'continuous' and 'list'
        'type': 'list',
        'values': [5]
    },
    'height_limit': {           # supported types are 'continuous' and 'list'
        'type': 'list',
        'values': [10, 20]
    },
    'grow_after_tick': False,
    'kelp_count': 1000,
    'test_time': {
        'min_value': 72000,
        'max_value': -1,
        'phase_limit': 1000,    # test_time = max(phase_limit * harvest_period, test_time)
        },
    'keys': ['harvest_period','height_limit']
}

# %% [markdown]
# Below codes simulate a system with configured conditions, modelling a kelp farm.

# %%
class KelpFarm():
    segement_size = 16**2
    subchunk_size = 16**3
    def __init__(self, kelps: dict, config: dict) -> None:
        self.kelp_count = config['kelp_count']
        self.tick_speed = config['tick_speed']
        self.harvest_period = config['harvest_period']
        self.empty_tick = config['empty_tick']
        self.height_limit = config['height_limit']
        self.grow_after_tick = config['grow_after_tick']
        self.test_time = max(config['test_time']['min_value'],             config['test_time']['phase_limit'] * config['harvest_period'])
        if config['test_time']['max_value'] > config['test_time']['min_value'] and config['test_time']['max_value'] < self.test_time:
            self.test_time = config['test_time']['max_value']

        self.kelps = kelps

        keys = config['keys']
        self.task_name = '[' + keys[0] + '=' + str(config[keys[0]]) + ' ' +            keys[1] + '=' + str(config[keys[1]]) + ' ' +            keys[2] + '=' + str(config[keys[2]]) + ']'
        #counter   
        self.item_count = 0
        self.tick_empty = 0
        self.tick_harvest = self.harvest_period


    def task_name(self) -> str:
            return self.task_name

    def start(self) -> tuple:
        lo = multiprocessing.Lock()
        lo.acquire()
        print('Start simulating:' + self.task_name + '\n', end='')
        lo.release()
        return self.simulate()
    
    def simulate(self) -> tuple:
        for self.gametick in range(self.test_time):
            self.tick()    
        eff = decimal.Decimal(self.item_count) / decimal.Decimal(self.kelp_count * self.test_time / 72000.)
        return(config, eff)

    def tick(self):
        global kelps
        if(self.grow_after_tick):
            self.tick_harvest -= 1 # harvest counter
            if self.tick_empty > 0:
                self.tick_empty -= 1 # scheduled tick
            if self.tick_empty <= 0:
                for i in range(self.tick_speed):  # grow(random tick)
                    selection = []
                    for segement in range(int(self.kelp_count / KelpFarm.segement_size) + 1):
                        index = random.randrange(0, KelpFarm.subchunk_size) + segement * KelpFarm.segement_size
                        if index < ((segement + 1) * KelpFarm.segement_size) and index < self.kelp_count:
                            selection.append(self.kelps[index])
                    for kelp in selection:
                        kelp.tick()
        else:
            self.tick_harvest -= 1 # harvest counter
            if self.tick_empty <= 0:
                for i in range(self.tick_speed):  # grow(random tick)
                    selection = []
                    for segement in range(int(self.kelp_count / KelpFarm.segement_size) + 1):
                        index = random.randrange(0, KelpFarm.subchunk_size) + segement * KelpFarm.segement_size
                        if index < ((segement + 1) * KelpFarm.segement_size) and index < self.kelp_count:
                            selection.append(self.kelps[index])
                    for kelp in selection:
                        kelp.tick()
            if self.tick_empty > 0:
                self.tick_empty -= 1 # scheduled tick
        if self.tick_harvest <= 0:
            items = 0
            for kelp in self.kelps:
                items += min(kelp.harvest(), self.height_limit)
            self.item_count += items
            self.tick_harvest = self.harvest_period # piston
            self.tick_empty = self.empty_tick
        if ((self.gametick + 1) % 72000) == 0:
            lo = multiprocessing.Lock()
            lo.acquire()
            print(self.task_name + 'Warped ' + str(int((self.gametick + 1) / 72000)) + ' hour(s)..\n', end= '')
            lo.release()
        


# %%
# global variables
kelps = []
result = []
temp_result = []

# %% [markdown]
# Some works to do before the calculation start:
# 
# - Load the config(json) from file for the test condition.
# - Initialize the test condition, translate some configuration so further calculation is easier.
# 

# %%
def load_config(path: str):
    global config
    try:
        with open(path) as file:
            temp = json.load(file)
        config = temp
    except:
        with open(path,'w') as file:
            json.dump(config, file, indent=4)
        print("Config not found, automatically generate default.")
        exit()

def translate_list(val: dict) -> list:
    if val['type'] == 'list':
        return val['values']
    elif val['type'] == 'continuous':
        return range(val['start'], val['end'], val['step'])

def init():
    global config, kelps
    key_1: str
    key_2: str
    key_3: str
    accepted_keys = ['harvest_period', 'empty_tick', 'height_limit']
    if len(config['keys']) >= 2:
        [key_1 , key_2] = config['keys'][0:2]
        if (key_1 not in accepted_keys) and (key_2 not in accepted_keys):
            print("Error in config \'key\'")
            exit()
        accepted_keys.remove(key_1)
        accepted_keys.remove(key_2)
        key_3 = accepted_keys[0]
        config['keys'] = [key_1, key_2, key_3]
    else:
        print("Error in config \'key\'")
        exit()

    config['harvest_period'] = translate_list(config['harvest_period'])
    config['empty_tick'] = translate_list(config['empty_tick'])
    config['height_limit'] = translate_list(config['height_limit'])

    os.makedirs('./multiProcessResults', exist_ok=True)

    for i in range(config['kelp_count']):
        kelp = Kelp()
        kelp.reset
        kelps.append(kelp)
    print('Starting test with %d kelp plants'%config['kelp_count'])

# %% [markdown]
# There are 3 configurable variables that can have multiple input value, so 3 for-loops to handle them. To make data easier to use, the results are exported to csv tables.

# %%
def write_csv(result:dict, config: dict, key_3):
    path = './multiProcessResults/' + config['keys'][2] + '=' + str(key_3) +            '[' + config['keys'][0] + ','+ config['keys'][1] + ']' +        time.asctime().replace(':','.')
    with open(path + '.csv','w',newline='') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerow([config['keys'][1] + '|' + config['keys'][0]] +            list(config[config['keys'][0]]))
        for key_2 in config[config['keys'][1]]:
            writer.writerow([key_2] + result[key_2])

def start(pool: multiprocessing.Pool):
    global config, result, temp_result, kelps
    conf = config.copy()
    for key_3 in config[config['keys'][2]]:
        conf[config['keys'][2]] = key_3
        result = {}
        for key_2 in config[config['keys'][1]]:
            conf[config['keys'][1]] = key_2

            task_farms = []
            for key_1 in config[config['keys'][0]]:
                conf[config['keys'][0]] = key_1
                task_farms.append(KelpFarm(kelps.copy(),conf.copy()))
            
            async_result = pool.map_async(KelpFarm.start, task_farms)
            # collect data
            temp_result = async_result.get()
            temp_list = []
            for (c, val) in temp_result:
                temp_list.append(val)
            result[key_2] = temp_list

        write_csv(result, config, key_3)

# %% [markdown]
# Nothing here because the data is supposed to be handled later.

# %%
def show_result():
    print('DONE!\n')
    pass


# %%
# structure
load_config("./config-multiprocess.json")
init()
with multiprocessing.Pool(processes=config['process_count']) as pool:
    start(pool)
show_result()


