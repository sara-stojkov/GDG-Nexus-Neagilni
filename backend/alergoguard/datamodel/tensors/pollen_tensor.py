import torch
import numpy as np

class PollenTensorizer:
    def __init__(self, species_list):
        """
        species_list: fixed ordering of pollen types
        e.g. ["nettle", "grass", "trees"]
        """
        self.species_list = species_list
        self.index = {s: i for i, s in enumerate(species_list)}

    def transform(self, pollen_dict):
        """
        pollen_dict: {species: concentration}
        returns: tensor [num_species]
        """
        x = np.zeros(len(self.species_list), dtype=np.float32)

        for k, v in pollen_dict.items():
            if k in self.index:
                x[self.index[k]] = v

        return torch.tensor(x)
