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

# %% [markdown]
# What we have known:
# - Random tick ticks 3 times a gametick in every subchunk, 1 block each time, and duplication is allowed.
#     - Therefore, the probability of a random tick selects exactly one specific block is `p = 1 / (16 ** 3)` 
# - A kelp block has 14% chance to grow 1 block if randomly ticked once.
# - A kelp block stops growing when *age* >= 25, and *age* increase 1 each time it grows.
# - Kelp obtians random *age* between [0,24] when it is placed, not grow.

# %%
class Kelp():
    growth_probability = 0.14
    seletct_probability = 1/(16**3)
    segement_size = 16**2
    subchunk_size = 16**3
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
# ``` Python
# tick_speed = 3
# harvest_period = 10 
#     # gametick
# empty_tick = 5 
#     # water flow 5gt
# height_limit = 10
#     # max height allowed in this farm
# grow_after_tick = False 
#     # Fasle: 1.15~1.16.5 behavior, random tick before scheduled tick; True: 1.15- & 1.17.x behavior, random tick after schedueled tick
# kelp_count = 10000
# test_time = 72000 * 1000 
#     # gametick
# # ------------
# item_counter: int
# gametick: int
# ```
# 

# %%
config = {
    'process_count': 4,
    'tick_speed': 3,
    'harvest_period': {
        'type': 'continuous',
        'start': 600,
        'end': 3600,
        'step': 600
    },
    'empty_tick': {
        'type': 'list',
        'values': [5]
    },
    'height_limit': {
        'type': 'list',
        'values': [10, 20]
    },
    'grow_after_tick': False,
    'kelp_count': 1000,
    'test_time': 72000,
    'keys': ['harvest_period','height_limit']
}
# supported types are 'continuous' and 'list'


# %%
# global variables
kelps = []
result = []
temp_result = []


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
        kelps.append(Kelp())
    print('Starting test with %d kelp plants'%config['kelp_count'])


# %%
def write_csv(result:dict, config: dict, key_3):
    path = './multiProcessResults/' + config['keys'][2] + '=' + str(key_3) +            '[' + config['keys'][0] + ','+ config['keys'][1] + ']' +        str(int(time.time()))
    with open(path + '.csv','w',newline='') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerow([config['keys'][1] + '|' + config['keys'][0]] +            list(config[config['keys'][0]]))
        for key_2 in config[config['keys'][1]]:
            writer.writerow([key_2] + result[key_2])

def start(pool: multiprocessing.Pool):
    global config, result, temp_result
    conf = config.copy()
    for key_3 in config[config['keys'][2]]:
        conf[config['keys'][2]] = key_3
        result = {}
        for key_2 in config[config['keys'][1]]:
            conf[config['keys'][1]] = key_2
            task_configs = []
            for key_1 in config[config['keys'][0]]:
                conf[config['keys'][0]] = key_1
                task_configs.append((conf.copy(), [key_1,key_2,key_3]))
            
            async_result = pool.starmap_async(sim_task,task_configs)
            # collect data
            temp_result = async_result.get()
            temp_list = []
            for (c, val) in temp_result:
                temp_list.append(val)
            result[key_2] = temp_list

        write_csv(result, config, key_3)
            
def sim_task(conf: dict, keys: list) -> tuple:
    task_name = '[' + conf['keys'][0] + '=' + str(keys[0]) + ' ' +    conf['keys'][1] + '=' + str(keys[1]) + ' ' +    conf['keys'][2] + '=' + str(keys[2]) + ']'
    lo = multiprocessing.Lock()
    lo.acquire()
    print('Start simulating:' + task_name + '\n', end='')
    lo.release()
    return simulate(conf, task_name)


# %%
def simulate(config: dict, task_name: str) -> tuple:
    global kelps, temp_result
    gt: int
    # print(config)
    for kelp in kelps:
        kelp.reset()
    counters = {
        'item': 0,
        'empty': 0,
        'harvest': config['harvest_period']
    }
    for gt in range(config['test_time']):
        tick(gt, counters, config, task_name)    
    eff = decimal.Decimal(counters['item']) / decimal.Decimal(config['kelp_count'] * config['test_time'] / 72000.)
    return(config, eff)

def tick(gametick: int, counters: dict, config: dict, task_name: str):
    global kelps
    if(config['grow_after_tick']):
        counters['harvest'] -= 1 # harvest counter
        if counters['empty'] > 0:
            counters['empty'] -= 1 # scheduled tick
        if counters['empty'] <= 0:
            for i in range(config['tick_speed']):  # grow(random tick)
                selection = []
                for segement in range(int(config['kelp_count'] / Kelp.segement_size) + 1):
                    index = random.randrange(0, Kelp.subchunk_size) + segement * Kelp.segement_size
                    if index < ((segement + 1) * Kelp.segement_size) and index < config['kelp_count']:
                        selection.append(kelps[index])
                for kelp in selection:
                    kelp.tick()
    else:
        counters['harvest'] -= 1 # harvest counter
        if counters['empty'] <= 0:
            for i in range(config['tick_speed']):  # grow(random tick)
                selection = []
                for segement in range(int(config['kelp_count'] / Kelp.segement_size) + 1):
                    index = random.randrange(0, Kelp.subchunk_size) + segement * Kelp.segement_size
                    if index < ((segement + 1) * Kelp.segement_size) and index < config['kelp_count']:
                        selection.append(kelps[index])
                for kelp in selection:
                    kelp.tick()
        if counters['empty'] > 0:
            counters['empty'] -= 1 # scheduled tick
    if counters['harvest'] <= 0:
        items = 0
        for kelp in kelps:
            items += min(kelp.harvest(), config['height_limit'])
        counters['item'] += items
        counters['harvest'] = config['harvest_period'] # piston
        counters['empty'] = config['empty_tick']
    if ((gametick + 1) % 72000) == 0:
        lo = multiprocessing.Lock()
        lo.acquire()
        print(task_name + 'Warped ' + str(int((gametick + 1) / 72000)) + ' hour(s)..\n', end= '')
        lo.release()
    


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


