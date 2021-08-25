# To add a new cell, type '# %%'
# To add a new markdown cell, type '# %% [markdown]'
# %%
import csv
import random
import json
import decimal

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
    def __init__(self) -> None:
        self.init()

    def init(self):
        self.age = random.randint(0,24)
        self.start_age = self.age

    def tick(self):
        if self.age < 25 and random.random() < (Kelp.growth_probability * Kelp.seletct_probability):
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
# %% [markdown]
# ```

# %%
config = {
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
    pass

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

    for i in range(config['kelp_count']):
        kelps.append(Kelp())
    print('Starting test with %d kelp plants'%config['kelp_count'])


# %%
def start():
    global config, result, temp_result
    conf = config.copy()
    for key_3 in config[config['keys'][2]]:
        conf[config['keys'][2]] = key_3
        result = {}
        for key_2 in config[config['keys'][1]]:
            conf[config['keys'][1]] = key_2
            for key_1 in config[config['keys'][0]]:
                conf[config['keys'][0]] = key_1
                print('Start simulating:' +                    config['keys'][0] + '=' + str(key_1) + ' ' +                    config['keys'][1] + '=' + str(key_2) + ' ' +                    config['keys'][2] + '=' + str(key_3) + ' '
                    )
                simulate(conf)
            # collect data
            temp_list = []
            for (c,val) in temp_result:
                temp_list.append(val)
            temp_result = []
            result[key_2] = temp_list

        path = './' + config['keys'][2] + '=' + str(key_3) +             '<' + config['keys'][0] + ','+ config['keys'][1] + '>'
        with open(path + '.csv','w',newline='') as csvfile:
            writer = csv.writer(csvfile)
            writer.writerow([config['keys'][1] + '|' + config['keys'][0]] +                list(config[config['keys'][0]]))
            for key_2 in config[config['keys'][1]]:
                writer.writerows([key_2] + result[key_2])
            
        
            


# %%
def simulate(config: dict):
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
        tick(gt, counters, config)    
    eff = decimal.Decimal(counters['item']) / (config['kelp_count'] * config['test_time'] / 72000)
    temp_result.append((config, eff))

def tick(gametick: int, counters: dict, config: dict):
    global kelps
    if(config['grow_after_tick']):
        if counters['empty'] > 0:
            counters['empty'] -= 1 # scheduled tick
        counters['harvest'] -= 1 # harvest counter
        if counters['empty'] <= 0:
            for i in range(config['tick_speed']):
                for kelp in kelps:
                    kelp.tick() # grow(random tick)
    else:
        if counters['empty'] <= 0:
            for i in range(config['tick_speed']):
                for kelp in kelps:
                    kelp.tick() # grow(random tick)
        if counters['empty'] > 0:
            counters['empty'] -= 1 # scheduled tick
        counters['harvest'] -= 1 # harvest counter
    if counters['harvest'] <= 0:
        items = 0
        for kelp in kelps:
            items += min(kelp.harvest(), config['height_limit'])
        counters['item'] += items
        counters['harvest'] = config['harvest_period'] # piston
    if ((gametick + 1) % 72000) == 0:
        print('Warped ' + str(int((gametick + 1) / 72000)) + ' hour(s)..')
    


# %%
def show_result():
    print('DONE!\n')
    pass


# %%
# structure
load_config("./config.json")
init()
start()
show_result()


